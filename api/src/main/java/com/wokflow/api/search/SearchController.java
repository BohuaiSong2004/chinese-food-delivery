package com.wokflow.api.search;

import com.wokflow.api.common.ApiException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchController {
    private static final Pattern FACET_VALUE = Pattern.compile("[a-z0-9-]{1,32}");
    private final SearchService search;

    public SearchController(SearchService search) { this.search = search; }

    @GetMapping("/api/search")
    SearchService.Result search(@RequestParam(required = false) String q,
                                @RequestParam(required = false) List<String> tag,
                                @RequestParam(required = false) List<String> cuisine,
                                @RequestParam(required = false) Integer maxSpice,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(required = false) Double lat,
                                @RequestParam(required = false) Double lng) {
        if (q != null && q.length() > 100) {
            throw ApiException.badRequest("query_too_long", "Search text is limited to 100 characters");
        }
        List<String> tags = clean(tag);
        List<String> cuisines = clean(cuisine);
        if (maxSpice != null && (maxSpice < 0 || maxSpice > 3)) {
            throw ApiException.badRequest("bad_spice", "maxSpice must be 0 to 3");
        }
        return search.search(new SearchService.Criteria(q, tags, cuisines, maxSpice, Math.clamp(page, 0, 50),
                Math.clamp(size, 1, 50), com.wokflow.api.catalog.CatalogController.point(lat, lng)));
    }

    private static List<String> clean(List<String> values) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > 10 || values.stream().anyMatch(v -> !FACET_VALUE.matcher(v).matches())) {
            throw ApiException.badRequest("bad_filter", "Invalid filter value");
        }
        return values.stream().distinct().toList();
    }
}
