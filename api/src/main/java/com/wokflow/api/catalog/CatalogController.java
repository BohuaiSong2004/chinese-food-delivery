package com.wokflow.api.catalog;

import com.wokflow.api.catalog.CatalogViews.MenuView;
import com.wokflow.api.catalog.CatalogViews.RestaurantView;
import com.wokflow.api.hot.HotMenuService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {
    private final CatalogService catalog;
    private final HotMenuService hot;

    public CatalogController(CatalogService catalog, HotMenuService hot) {
        this.catalog = catalog;
        this.hot = hot;
    }

    /** lat/lng (the customer's delivery point, rounded by the client) add fee, distance and ETA. */
    @GetMapping("/api/restaurants")
    List<RestaurantView> restaurants(@RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return catalog.restaurants(point(lat, lng));
    }

    @GetMapping("/api/restaurants/{slug}")
    MenuView menu(@PathVariable String slug, @RequestParam(required = false) Double lat,
                  @RequestParam(required = false) Double lng) {
        return catalog.menu(slug, point(lat, lng));
    }

    public static com.wokflow.api.geo.GeoPoint point(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        try {
            return new com.wokflow.api.geo.GeoPoint(lat, lng);
        } catch (IllegalArgumentException e) {
            throw com.wokflow.api.common.ApiException.badRequest("bad_location", "lat/lng is not a coordinate");
        }
    }

    @GetMapping("/api/trending")
    List<HotMenuService.TrendingDish> trending(@RequestParam(defaultValue = "8") int limit) {
        return hot.trending(Math.clamp(limit, 1, 20));
    }
}
