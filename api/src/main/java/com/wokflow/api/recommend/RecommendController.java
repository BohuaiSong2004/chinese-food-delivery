package com.wokflow.api.recommend;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.auth.Role;
import com.wokflow.api.catalog.CatalogController;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.images.ImageService;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RecommendController {
    static final int REORDER_LIMIT = 8;

    /** A dish the customer has ordered before and can order again now. */
    public record PastDish(long dishId, String name, String nameZh, int priceCents, String image, int times,
                           java.time.Instant lastOrderedAt, long restaurantId, String restaurantSlug,
                           String restaurantName, String restaurantNameZh, boolean acceptingOrders) {}

    private final Pairings pairings;
    private final JdbcClient db;
    private final ImageService images;

    public RecommendController(Pairings pairings, JdbcClient db, ImageService images) {
        this.pairings = pairings;
        this.db = db;
        this.images = images;
    }

    /** "Goes well with": for the dishes in a cart, others of the same restaurant often ordered with them. */
    @GetMapping("/api/restaurants/{slug}/pairings")
    List<Pairings.Pairing> pairings(@PathVariable String slug, @RequestParam(name = "dish") List<Long> dishes) {
        if (dishes.isEmpty() || dishes.size() > 50) {
            throw ApiException.badRequest("bad_dishes", "Give 1 to 50 dish ids");
        }
        Long rid = db.sql("select id from restaurants where slug = :slug and status = 'LIVE'")
                .param("slug", slug).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("Restaurant"));
        return pairings.forDishes(rid, dishes.stream().distinct().toList());
    }

    /**
     * "Order again": dishes from the caller's paid orders, most recent first, that are still on a
     * live menu, and — when lat/lng are given — from kitchens that deliver there.
     */
    @GetMapping("/api/me/reorder")
    List<PastDish> reorder(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        CurrentUser me = CurrentUser.require();
        if (!me.is(Role.CUSTOMER)) {
            return List.of();
        }
        GeoPoint to = CatalogController.point(lat, lng);
        return db.sql("""
                select i.dish_id, d.name, d.name_zh, d.price_cents, d.image_key,
                       count(distinct o.id)::int as times, max(o.created_at) as last_at,
                       r.id as rid, r.slug, r.name as rname, r.name_zh as rname_zh, r.accepting_orders
                from orders o
                join order_items i on i.order_id = o.id
                join dishes d on d.id = i.dish_id
                join restaurants r on r.id = o.restaurant_id
                where o.customer_id = :uid
                  and o.status in ('PAID', 'ACCEPTED', 'READY', 'PICKED_UP', 'DELIVERED')
                  and d.available and d.archived_at is null and r.status = 'LIVE'
                  and (:lat::float8 is null or (r.lat is not null and 2 * 6371008.8 * asin(least(1, sqrt(
                        power(sin(radians(r.lat - :lat::float8) / 2), 2)
                        + cos(radians(:lat::float8)) * cos(radians(r.lat)) * power(sin(radians(r.lng - :lng::float8) / 2), 2))))
                        <= r.delivery_radius_km * 1000))
                group by i.dish_id, d.name, d.name_zh, d.price_cents, d.image_key, r.id, r.slug, r.name, r.name_zh, r.accepting_orders
                order by last_at desc, i.dish_id
                limit :limit""")
                .param("uid", me.id()).param("limit", REORDER_LIMIT)
                .param("lat", to == null ? null : to.lat()).param("lng", to == null ? null : to.lng())
                .query((rs, n) -> new PastDish(rs.getLong("dish_id"), rs.getString("name"), rs.getString("name_zh"),
                        rs.getInt("price_cents"), images.url(rs.getString("image_key")), rs.getInt("times"),
                        rs.getTimestamp("last_at").toInstant(), rs.getLong("rid"), rs.getString("slug"),
                        rs.getString("rname"), rs.getString("rname_zh"), rs.getBoolean("accepting_orders")))
                .list();
    }
}
