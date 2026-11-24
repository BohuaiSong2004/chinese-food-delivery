package com.wokflow.api.catalog;

import com.wokflow.api.catalog.CatalogViews.CachedMenu;
import com.wokflow.api.catalog.CatalogViews.CategoryView;
import com.wokflow.api.catalog.CatalogViews.DishView;
import com.wokflow.api.catalog.CatalogViews.MenuView;
import com.wokflow.api.catalog.CatalogViews.RestaurantView;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.hot.HotMenuService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class CatalogService {
    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);
    static final Duration MENU_TTL = Duration.ofMinutes(10);

    private final CatalogRepository repo;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final HotMenuService hot;
    private final com.wokflow.api.images.ImageService images;
    private final com.wokflow.api.geo.DeliveryPricing pricing;
    private final Clock clock;
    private final Counter hits;
    private final Counter misses;

    public CatalogService(CatalogRepository repo, StringRedisTemplate redis, ObjectMapper json, HotMenuService hot,
                          Clock clock, MeterRegistry meters, com.wokflow.api.images.ImageService images,
                          com.wokflow.api.geo.DeliveryPricing pricing) {
        this.images = images;
        this.pricing = pricing;
        this.repo = repo;
        this.redis = redis;
        this.json = json;
        this.hot = hot;
        this.clock = clock;
        this.hits = meters.counter("wokflow.menu.cache", "result", "hit");
        this.misses = meters.counter("wokflow.menu.cache", "result", "miss");
    }

    /** Versioned key: a menu change bumps menu_version, so an old cached copy is simply never read again. */
    static String menuKey(long restaurantId, long menuVersion) { return "menu:v2:" + restaurantId + ":" + menuVersion; }

    public List<RestaurantView> restaurants() { return restaurants(null); }

    /**
     * The storefront. With a delivery point, each restaurant carries its fee, distance and ETA, and
     * the list puts restaurants that deliver there first, nearest first.
     */
    public List<RestaurantView> restaurants(com.wokflow.api.geo.GeoPoint to) {
        Instant now = clock.instant();
        var views = repo.liveRestaurants().stream()
                .map(r -> RestaurantView.of(r, now, images.url(r.coverKey()), to == null ? null : pricing.quote(r, to).orElse(null)))
                .toList();
        if (to == null) {
            return views;
        }
        return views.stream().sorted(java.util.Comparator
                .comparing((RestaurantView v) -> v.delivery() == null || !v.delivery().deliverable())
                .thenComparingInt(v -> v.delivery() == null ? Integer.MAX_VALUE : v.delivery().distanceM())).toList();
    }

    public MenuView menu(String slug) { return menu(slug, null); }

    public MenuView menu(String slug, com.wokflow.api.geo.GeoPoint to) {
        long id = repo.restaurantBySlug(slug).filter(Restaurant::live).map(Restaurant::id)
                .orElseThrow(() -> ApiException.notFound("Restaurant"));
        MenuView m = menu(id);
        if (to == null) {
            return m;
        }
        Restaurant r = repo.restaurant(id).orElseThrow();
        var rv = RestaurantView.of(r, clock.instant(), m.restaurant().image(), pricing.quote(r, to).orElse(null));
        return new MenuView(rv, m.categories(), m.popular());
    }

    /** Menu structure comes from Redis (falls back to Postgres); stock and popularity are always live. */
    public MenuView menu(long restaurantId) {
        CachedMenu cached = cachedMenu(restaurantId);
        Map<Long, Integer> stock = repo.stock(restaurantId);
        List<CategoryView> categories = cached.categories().stream()
                .map(c -> new CategoryView(c.id(), c.name(), c.nameZh(), c.dishes().stream()
                        .map(d -> d.limited() ? withStock(d, stock.get(d.id())) : d).toList()))
                .toList();
        return new MenuView(RestaurantView.of(cached.restaurant(), clock.instant(), images.url(cached.restaurant().coverKey())), categories,
                hot.top(restaurantId, cached));
    }

    CachedMenu cachedMenu(long restaurantId) {
        String key = menuKey(restaurantId, repo.menuVersion(restaurantId));
        try {
            String hit = redis.opsForValue().get(key);
            if (hit != null) {
                hits.increment();
                return json.readValue(hit, CachedMenu.class);
            }
        } catch (RuntimeException e) {
            log.warn("Menu cache read failed, using Postgres: {}", e.toString());
        }
        misses.increment();
        CachedMenu fresh = load(restaurantId);
        try {
            redis.opsForValue().set(key, json.writeValueAsString(fresh), MENU_TTL);
        } catch (RuntimeException e) {
            log.warn("Menu cache write failed: {}", e.toString());
        }
        return fresh;
    }

    CachedMenu load(long restaurantId) {
        Restaurant r = repo.restaurant(restaurantId).orElseThrow(() -> ApiException.notFound("Restaurant"));
        Map<Long, List<Dish>> byCategory = repo.dishes(restaurantId).stream()
                .collect(Collectors.groupingBy(Dish::categoryId));
        List<CategoryView> categories = repo.categories(restaurantId).stream()
                .map(c -> new CategoryView(c.id(), c.name(), c.nameZh(),
                        byCategory.getOrDefault(c.id(), List.of()).stream().map(this::view).toList()))
                .filter(c -> !c.dishes().isEmpty())
                .toList();
        return new CachedMenu(r, categories);
    }

    DishView view(Dish d) {
        return new DishView(d.id(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.available(), d.limited(), null, images.url(d.imageKey()));
    }

    private static DishView withStock(DishView d, Integer remaining) {
        return new DishView(d.id(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.available(), true, remaining == null ? 0 : remaining, d.image());
    }
}
