package com.wokflow.api.catalog;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import com.wokflow.api.outbox.Outbox;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The kitchen's menu editor. Every change bumps the restaurant's menu_version (so the cached public
 * menu is never read again) and writes an outbox event (so the search index follows), in the same
 * transaction as the change itself. Edits are optimistic on the row version; items are archived,
 * never deleted, because past orders refer to them.
 */
@Service
public class MenuEditor {
    public record CategoryInput(@NotBlank @Size(max = 60) String name, @NotBlank @Size(max = 30) String nameZh) {}

    public record DishInput(@Positive long categoryId,
                            @NotBlank @Size(max = 80) String name,
                            @NotBlank @Size(max = 40) String nameZh,
                            @Size(max = 300) String description,
                            @Min(1) @Max(100_000) int priceCents,
                            @Size(max = 6) List<String> tags,
                            @Min(0) @Max(3) int spiceLevel,
                            boolean available,
                            @Min(1) @Max(10_000) Integer dailyStock) {}

    static final int MAX_CATEGORIES = 30;
    static final int MAX_DISHES = 300;

    private final JdbcClient db;
    private final CatalogRepository catalog;
    private final Outbox outbox;
    private final Tx tx;
    private final Clock clock;

    public MenuEditor(JdbcClient db, CatalogRepository catalog, Outbox outbox, Tx tx, Clock clock) {
        this.db = db;
        this.catalog = catalog;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ categories

    public CatalogRepository.Category createCategory(CurrentUser u, long restaurantId, CategoryInput in) {
        u.requireStaffOf(restaurantId);
        long id = tx.run(() -> {
            lockMenu(restaurantId);
            long count = db.sql("select count(*) from menu_categories where restaurant_id = ? and archived_at is null")
                    .param(restaurantId).query(Long.class).single();
            if (count >= MAX_CATEGORIES) {
                throw ApiException.conflict("too_many_categories", "A menu can have at most " + MAX_CATEGORIES + " sections");
            }
            long newId = unique(() -> db.sql("""
                    insert into menu_categories (restaurant_id, name, name_zh, position)
                    values (?, ?, ?, (select coalesce(max(position) + 1, 0) from menu_categories where restaurant_id = ?))
                    returning id""")
                    .params(restaurantId, in.name().strip(), in.nameZh().strip(), restaurantId).query(Long.class).single());
            changed(restaurantId);
            return newId;
        });
        return category(restaurantId, id);
    }

    public CatalogRepository.Category updateCategory(CurrentUser u, long categoryId, CategoryInput in, long version) {
        long rid = categoryRestaurant(categoryId);
        u.requireStaffOf(rid);
        tx.runVoid(() -> {
            int n = unique(() -> db.sql("""
                    update menu_categories set name = ?, name_zh = ?, version = version + 1
                    where id = ? and version = ? and archived_at is null""")
                    .params(in.name().strip(), in.nameZh().strip(), categoryId, version).update());
            if (n == 0) {
                throw stale("section");
            }
            changed(rid);
        });
        return category(rid, categoryId);
    }

    /** Sets the display order; the list must name every section exactly once. */
    public List<CatalogRepository.Category> reorder(CurrentUser u, long restaurantId, List<Long> ids) {
        u.requireStaffOf(restaurantId);
        tx.runVoid(() -> {
            lockMenu(restaurantId);
            var current = new HashSet<>(catalog.categories(restaurantId).stream().map(CatalogRepository.Category::id).toList());
            if (ids.size() != current.size() || !current.equals(new HashSet<>(ids))) {
                throw ApiException.conflict("stale_order", "The sections changed; reload and try again");
            }
            for (int i = 0; i < ids.size(); i++) {
                db.sql("update menu_categories set position = ?, version = version + 1 where id = ?").params(i, ids.get(i)).update();
            }
            changed(restaurantId);
        });
        return catalog.categories(restaurantId);
    }

    public void archiveCategory(CurrentUser u, long categoryId) {
        long rid = categoryRestaurant(categoryId);
        u.requireStaffOf(rid);
        tx.runVoid(() -> {
            lockMenu(rid);
            long dishes = db.sql("select count(*) from dishes where category_id = ? and archived_at is null")
                    .param(categoryId).query(Long.class).single();
            if (dishes > 0) {
                throw ApiException.conflict("section_not_empty", "Move or remove the " + dishes + " dishes in this section first");
            }
            db.sql("update menu_categories set archived_at = now(), version = version + 1 where id = ? and archived_at is null")
                    .param(categoryId).update();
            changed(rid);
        });
    }

    // ------------------------------------------------------------------ dishes

    public Dish createDish(CurrentUser u, long restaurantId, DishInput in) {
        u.requireStaffOf(restaurantId);
        long id = tx.run(() -> {
            lockMenu(restaurantId);
            requireCategoryOf(restaurantId, in.categoryId());
            long count = db.sql("select count(*) from dishes where restaurant_id = ? and archived_at is null")
                    .param(restaurantId).query(Long.class).single();
            if (count >= MAX_DISHES) {
                throw ApiException.conflict("too_many_dishes", "A menu can have at most " + MAX_DISHES + " dishes");
            }
            long newId = unique(() -> db.sql("""
                    insert into dishes (restaurant_id, category_id, name, name_zh, description, price_cents, tags, spice_level,
                                        available, daily_stock, stock_remaining, stock_day)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id""")
                    .params(restaurantId, in.categoryId(), in.name().strip(), in.nameZh().strip(), description(in),
                            in.priceCents(), Tags.check(in.tags()).toArray(String[]::new), in.spiceLevel(), in.available(),
                            in.dailyStock(), in.dailyStock(), in.dailyStock() == null ? null : localToday(restaurantId))
                    .query(Long.class).single());
            dishChanged(restaurantId, newId);
            return newId;
        });
        return catalog.dish(id).orElseThrow();
    }

    /**
     * Full edit. A new daily limit moves today's remaining stock by the same amount (selling 10 of 40
     * then raising the limit to 50 leaves 40), in the same single UPDATE as the order path's decrement.
     */
    public Dish updateDish(CurrentUser u, long dishId, DishInput in, long version) {
        Dish d = catalog.dish(dishId).filter(x -> !x.archived()).orElseThrow(() -> ApiException.notFound("Dish"));
        u.requireStaffOf(d.restaurantId());
        tx.runVoid(() -> {
            requireCategoryOf(d.restaurantId(), in.categoryId());
            int n = unique(() -> db.sql("""
                    update dishes set category_id = ?, name = ?, name_zh = ?, description = ?, price_cents = ?, tags = ?,
                           spice_level = ?, available = ?, daily_stock = ?,
                           stock_remaining = case
                               when ?::int is null then null
                               when daily_stock is null then ?::int
                               else greatest(0, coalesce(stock_remaining, 0) + (?::int - daily_stock)) end,
                           stock_day = case when ?::int is null then null else coalesce(stock_day, ?) end,
                           version = version + 1, updated_at = now()
                    where id = ? and version = ? and archived_at is null""")
                    .params(in.categoryId(), in.name().strip(), in.nameZh().strip(), description(in), in.priceCents(),
                            Tags.check(in.tags()).toArray(String[]::new), in.spiceLevel(), in.available(), in.dailyStock(),
                            in.dailyStock(), in.dailyStock(), in.dailyStock(), in.dailyStock(), localToday(d.restaurantId()),
                            dishId, version)
                    .update());
            if (n == 0) {
                throw stale("dish");
            }
            dishChanged(d.restaurantId(), dishId);
        });
        return catalog.dish(dishId).orElseThrow();
    }

    public void archiveDish(CurrentUser u, long dishId) {
        Dish d = catalog.dish(dishId).orElseThrow(() -> ApiException.notFound("Dish"));
        u.requireStaffOf(d.restaurantId());
        tx.runVoid(() -> {
            db.sql("update dishes set archived_at = now(), available = false, version = version + 1, updated_at = now() where id = ? and archived_at is null")
                    .param(dishId).update();
            dishChanged(d.restaurantId(), dishId);
        });
    }

    // ------------------------------------------------------------------ photos

    public Dish requireOwnDish(CurrentUser u, long dishId) {
        Dish d = catalog.dish(dishId).filter(x -> !x.archived()).orElseThrow(() -> ApiException.notFound("Dish"));
        u.requireStaffOf(d.restaurantId());
        return d;
    }

    /** Points the dish at an already-stored image (or none); the files themselves are immutable. */
    public Dish setDishImage(CurrentUser u, long dishId, String imageKey) {
        Dish d = catalog.dish(dishId).filter(x -> !x.archived()).orElseThrow(() -> ApiException.notFound("Dish"));
        u.requireStaffOf(d.restaurantId());
        tx.runVoid(() -> {
            db.sql("update dishes set image_key = ?, version = version + 1, updated_at = now() where id = ?")
                    .params(imageKey, dishId).update();
            dishChanged(d.restaurantId(), dishId);
        });
        return catalog.dish(dishId).orElseThrow();
    }

    public void setRestaurantImage(CurrentUser u, long restaurantId, String imageKey) {
        u.requireStaffOf(restaurantId);
        tx.runVoid(() -> {
            int n = db.sql("""
                    update restaurants set image_key = ?, version = version + 1, menu_version = menu_version + 1,
                        updated_at = now() where id = ?""").params(imageKey, restaurantId).update();
            if (n == 0) {
                throw ApiException.notFound("Restaurant");
            }
            outbox.publish(Outbox.RESTAURANT_CHANGED, Long.toString(restaurantId), Map.of("restaurantId", restaurantId));
        });
    }

    // ------------------------------------------------------------------ helpers

    /** One editor at a time per menu for the checks that count rows (limits, complete reorder lists). */
    private void lockMenu(long restaurantId) {
        db.sql("select id from restaurants where id = ? for update").param(restaurantId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Restaurant"));
    }

    private void changed(long restaurantId) {
        catalog.bumpMenuVersion(restaurantId);
    }

    private void dishChanged(long restaurantId, long dishId) {
        catalog.bumpMenuVersion(restaurantId);
        outbox.publish(Outbox.DISH_CHANGED, Long.toString(dishId), Map.of("dishId", dishId, "restaurantId", restaurantId));
    }

    private void requireCategoryOf(long restaurantId, long categoryId) {
        boolean ok = db.sql("select count(*) from menu_categories where id = ? and restaurant_id = ? and archived_at is null")
                .params(categoryId, restaurantId).query(Long.class).single() > 0;
        if (!ok) {
            throw ApiException.badRequest("unknown_section", "Section " + categoryId + " is not on this menu");
        }
    }

    private long categoryRestaurant(long categoryId) {
        return db.sql("select restaurant_id from menu_categories where id = ? and archived_at is null").param(categoryId)
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("Section"));
    }

    private CatalogRepository.Category category(long restaurantId, long id) {
        return catalog.categories(restaurantId).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    private LocalDate localToday(long restaurantId) {
        String tz = db.sql("select time_zone from restaurants where id = ?").param(restaurantId).query(String.class).single();
        return LocalDate.now(clock.withZone(ZoneId.of(tz)));
    }

    private static String description(DishInput in) {
        return in.description() == null ? "" : in.description().strip();
    }

    private static <T> T unique(Supplier<T> write) {
        try {
            return write.get();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("name_taken", "Something on this menu already has that name");
        }
    }

    private static ApiException stale(String what) {
        return ApiException.conflict("stale_version", "This " + what + " was changed elsewhere; reload and try again");
    }
}
