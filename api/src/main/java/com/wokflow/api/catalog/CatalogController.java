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

    @GetMapping("/api/restaurants")
    List<RestaurantView> restaurants() { return catalog.restaurants(); }

    @GetMapping("/api/restaurants/{slug}")
    MenuView menu(@PathVariable String slug) { return catalog.menu(slug); }

    @GetMapping("/api/trending")
    List<HotMenuService.TrendingDish> trending(@RequestParam(defaultValue = "8") int limit) {
        return hot.trending(Math.clamp(limit, 1, 20));
    }
}
