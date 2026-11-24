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
    private final Clock clock;
    private final Counter hits;
    private final Counter misses;

    public CatalogService(CatalogRepository repo, StringRedisTemplate redis, ObjectMapper json, HotMenuService hot,
                          Clock clock, MeterRegistry meters) {
        this.repo = repo;
        this.redis = redis;
        this.json = json;
        this.hot = hot;
        this.clock = clock;
        this.hits = meters.counter("wokflow.menu.cache", "result", "hit");
        this.misses = meters.counter("wokflow.menu.cache", "result", "miss");
    }

    /** Versioned key: a menu change bumps menu_version, so an old cached copy is simply never read again. */
    static String menuKey(long restaurantId, long menuVersion) { return "menu:v1:" + restaurantId + ":" + menuVersion; }

    public List<RestaurantView> restaurants() {
        Instant now = clock.instant();
        return repo.liveRestaurants().stream().map(r -> RestaurantView.of(r, now)).toList();
    }

    public MenuView menu(String slug) {
        long id = repo.restaurantBySlug(slug).filter(Restaurant::live).map(Restaurant::id)
                .orElseThrow(() -> ApiException.notFound("Restaurant"));
        return menu(id);
    }

    /** Menu structure comes from Redis (falls back to Postgres); stock and popularity are always live. */
    public MenuView menu(long restaurantId) {
        CachedMenu cached = cachedMenu(restaurantId);
        Map<Long, Integer> stock = repo.stock(restaurantId);
        List<CategoryView> categories = cached.categories().stream()
                .map(c -> new CategoryView(c.id(), c.name(), c.nameZh(), c.dishes().stream()
                        .map(d -> d.limited() ? withStock(d, stock.get(d.id())) : d).toList()))
                .toList();
        return new MenuView(RestaurantView.of(cached.restaurant(), clock.instant()), categories,
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
                        byCategory.getOrDefault(c.id(), List.of()).stream().map(CatalogService::view).toList()))
                .filter(c -> !c.dishes().isEmpty())
                .toList();
        return new CachedMenu(r, categories);
    }

    static DishView view(Dish d) {
        return new DishView(d.id(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.available(), d.limited(), null);
    }

    private static DishView withStock(DishView d, Integer remaining) {
        return new DishView(d.id(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.available(), true, remaining == null ? 0 : remaining);
    }
}
