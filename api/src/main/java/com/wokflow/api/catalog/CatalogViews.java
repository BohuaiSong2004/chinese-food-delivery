package com.wokflow.api.catalog;

import java.time.Instant;
import java.util.List;

public final class CatalogViews {
    private CatalogViews() {}

    public record RestaurantView(long id, String slug, String name, String nameZh, String description, String cuisine,
                                 List<String> tags, String neighborhood, String address, int deliveryFeeCents,
                                 int minOrderCents, int prepMinutes, boolean openNow, Instant nextOpening,
                                 boolean acceptingOrders, String hours) {

        static RestaurantView of(Restaurant r, Instant now) {
            String hours = r.open24h() ? "24h" : r.opensAt() + "–" + r.closesAt();
            boolean open = r.isOpenAt(now);
            return new RestaurantView(r.id(), r.slug(), r.name(), r.nameZh(), r.description(), r.cuisine(), r.tags(),
                    r.neighborhood(), r.address(), r.deliveryFeeCents(), r.minOrderCents(), r.prepMinutes(), open,
                    open ? null : r.nextOpening(now), r.acceptingOrders(), hours);
        }
    }

    public record DishView(long id, String name, String nameZh, String description, int priceCents, List<String> tags,
                           int spiceLevel, boolean available, boolean limited, Integer stockRemaining) {}

    public record CategoryView(long id, String name, String nameZh, List<DishView> dishes) {}

    /** What is cached in Redis: menu structure without live stock. */
    public record CachedMenu(Restaurant restaurant, List<CategoryView> categories) {}

    public record HotDish(long dishId, String name, String nameZh, int priceCents, long orders) {}

    public record MenuView(RestaurantView restaurant, List<CategoryView> categories, List<HotDish> popular) {}
}
