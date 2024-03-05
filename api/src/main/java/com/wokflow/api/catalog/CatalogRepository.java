package com.wokflow.api.catalog;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogRepository {
    public record Category(long id, String name, String nameZh, int position, long version) {}

    static final String RESTAURANT_COLS = """
            id, slug, name, name_zh, description, cuisine, tags, neighborhood, address, delivery_fee_cents,
            min_order_cents, prep_minutes, commission_bps, opens_at, closes_at, open_24h, time_zone,
            accepting_orders, version, status""";
    static final String DISH_COLS = """
            id, restaurant_id, category_id, name, name_zh, description, price_cents, tags, spice_level, available,
            daily_stock, stock_remaining, version, archived_at is not null as archived""";

    private final JdbcClient db;

    public CatalogRepository(JdbcClient db) { this.db = db; }

    /** Every restaurant in any onboarding state (staff and admin views). */
    public List<Restaurant> restaurants() {
        return db.sql("select " + RESTAURANT_COLS + " from restaurants order by name").query(CatalogRepository::restaurant).list();
    }

    /** The storefront: only restaurants that passed review and are not suspended. */
    public List<Restaurant> liveRestaurants() {
        return db.sql("select " + RESTAURANT_COLS + " from restaurants where status = 'LIVE' order by name")
                .query(CatalogRepository::restaurant).list();
    }

    public Optional<Restaurant> restaurant(long id) {
        return db.sql("select " + RESTAURANT_COLS + " from restaurants where id = ?").param(id)
                .query(CatalogRepository::restaurant).optional();
    }

    public Optional<Restaurant> restaurantBySlug(String slug) {
        return db.sql("select " + RESTAURANT_COLS + " from restaurants where slug = ?").param(slug)
                .query(CatalogRepository::restaurant).optional();
    }

    public List<Category> categories(long restaurantId) {
        return db.sql("select id, name, name_zh, position, version from menu_categories where restaurant_id = ? and archived_at is null order by position, id")
                .param(restaurantId)
                .query((rs, n) -> new Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getLong(5))).list();
    }

    public List<Dish> dishes(long restaurantId) {
        return db.sql("select " + DISH_COLS + " from dishes where restaurant_id = ? and archived_at is null order by category_id, id")
                .param(restaurantId).query(CatalogRepository::dish).list();
    }

    public List<Dish> allDishes() {
        return db.sql("select " + DISH_COLS + " from dishes order by id").query(CatalogRepository::dish).list();
    }

    public Optional<Dish> dish(long id) {
        return db.sql("select " + DISH_COLS + " from dishes where id = ?").param(id).query(CatalogRepository::dish).optional();
    }

    public List<Dish> dishesById(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.sql("select " + DISH_COLS + " from dishes where id = any(?) order by id")
                .param(ids.toArray(Long[]::new)).query(CatalogRepository::dish).list();
    }

    /** Live stock for limited dishes; never cached. */
    public Map<Long, Integer> stock(long restaurantId) {
        return db.sql("select id, stock_remaining from dishes where restaurant_id = ? and daily_stock is not null and archived_at is null")
                .param(restaurantId)
                .query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public String statusNote(long restaurantId) {
        return db.sql("select status_note from restaurants where id = ?").param(restaurantId).query(String.class)
                .optional().orElse(null);
    }

    public long menuVersion(long restaurantId) {
        return db.sql("select menu_version from restaurants where id = ?").param(restaurantId).query(Long.class)
                .optional().orElseThrow(() -> com.wokflow.api.common.ApiException.notFound("Restaurant"));
    }

    public void bumpMenuVersion(long restaurantId) {
        db.sql("update restaurants set menu_version = menu_version + 1 where id = ?").param(restaurantId).update();
    }

    public long dishCount() {
        return db.sql("select count(*) from dishes").query(Long.class).single();
    }

    /** Compare-and-set update from the merchant console; returns the new version or empty on a stale write. */
    public Optional<Long> updateDish(long dishId, long restaurantId, long expectedVersion, int priceCents,
                                     boolean available, Integer dailyStock) {
        return db.sql("""
                update dishes set price_cents = ?, available = ?, daily_stock = ?,
                       stock_remaining = case
                           when ?::int is null then null
                           when daily_stock is null then ?::int
                           else greatest(0, coalesce(stock_remaining, 0) + (?::int - daily_stock)) end,
                       version = version + 1, updated_at = now()
                where id = ? and restaurant_id = ? and version = ?
                returning version""")
                .params(priceCents, available, dailyStock, dailyStock, dailyStock, dailyStock, dishId, restaurantId, expectedVersion)
                .query(Long.class).optional();
    }

    public Optional<Long> setAccepting(long restaurantId, boolean accepting) {
        return db.sql("""
                update restaurants set accepting_orders = ?, version = version + 1, menu_version = menu_version + 1,
                       updated_at = now()
                where id = ? returning version""")
                .params(accepting, restaurantId).query(Long.class).optional();
    }

    /**
     * Resets limited stock for restaurants whose local date moved on. One
     * statement per restaurant so each only takes its own dish rows.
     */
    public List<Long> restockIfNewDay(long restaurantId, LocalDate localToday) {
        return db.sql("""
                update dishes set stock_remaining = daily_stock, stock_day = ?, updated_at = now()
                where restaurant_id = ? and daily_stock is not null and (stock_day is null or stock_day < ?)
                returning id""")
                .params(localToday, restaurantId, localToday).query(Long.class).list();
    }

    static Restaurant restaurant(ResultSet rs, int n) throws SQLException {
        return new Restaurant(rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("name_zh"),
                rs.getString("description"), rs.getString("cuisine"), strings(rs.getArray("tags")),
                rs.getString("neighborhood"), rs.getString("address"), rs.getInt("delivery_fee_cents"),
                rs.getInt("min_order_cents"), rs.getInt("prep_minutes"), rs.getInt("commission_bps"),
                rs.getTime("opens_at").toLocalTime(), rs.getTime("closes_at").toLocalTime(), rs.getBoolean("open_24h"),
                rs.getString("time_zone"), rs.getBoolean("accepting_orders"), rs.getLong("version"),
                RestaurantStatus.valueOf(rs.getString("status")));
    }

    static Dish dish(ResultSet rs, int n) throws SQLException {
        return new Dish(rs.getLong("id"), rs.getLong("restaurant_id"), rs.getLong("category_id"), rs.getString("name"),
                rs.getString("name_zh"), rs.getString("description"), rs.getInt("price_cents"),
                strings(rs.getArray("tags")), rs.getInt("spice_level"), rs.getBoolean("available"),
                (Integer) rs.getObject("daily_stock"), (Integer) rs.getObject("stock_remaining"), rs.getLong("version"),
                rs.getBoolean("archived"));
    }

    static List<String> strings(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[]) a.getArray());
    }
}
