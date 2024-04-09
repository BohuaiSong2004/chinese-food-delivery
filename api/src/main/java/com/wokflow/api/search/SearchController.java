package com.wokflow.api.search;

import com.wokflow.api.catalog.CatalogController;
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
    private final Suggestions suggestions;
    private final SearchMisses misses;

    public SearchController(SearchService search, Suggestions suggestions, SearchMisses misses) {
        this.search = search;
        this.suggestions = suggestions;
        this.misses = misses;
    }

    @GetMapping("/api/search")
    SearchService.Result search(@RequestParam(required = false) String q,
                                @RequestParam(required = false) List<String> tag,
                                @RequestParam(required = false) List<String> cuisine,
                                @RequestParam(required = false) Integer maxSpice,
                                @RequestParam(required = false) String sort,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @RequestParam(required = false) Double lat,
                                @RequestParam(required = false) Double lng) {
        checkLength(q);
        List<String> tags = clean(tag);
        List<String> cuisines = clean(cuisine);
        if (maxSpice != null && (maxSpice < 0 || maxSpice > 3)) {
            throw ApiException.badRequest("bad_spice", "maxSpice must be 0 to 3");
        }
        var point = CatalogController.point(lat, lng);
        SearchService.Sort order = SearchService.Sort.parse(sort);
        if (order == SearchService.Sort.DISTANCE && point == null) {
            throw ApiException.badRequest("no_location", "Sorting by distance needs lat and lng");
        }
        return search.search(new SearchService.Criteria(q, tags, cuisines, maxSpice, Math.clamp(page, 0, 50),
                Math.clamp(size, 1, 50), point, order, true));
    }

    /** Search-as-you-type: a few dishes and restaurants for what has been typed so far. */
    @GetMapping("/api/search/suggest")
    Suggestions.Result suggest(@RequestParam String q, @RequestParam(required = false) Double lat,
                               @RequestParam(required = false) Double lng) {
        checkLength(q);
        return suggestions.suggest(q, CatalogController.point(lat, lng));
    }

    /** Admin: what people searched for that matched nothing (see SearchMisses). */
    @GetMapping("/api/admin/search-misses")
    List<SearchMisses.Miss> misses(@RequestParam(defaultValue = "30") int days, @RequestParam(defaultValue = "50") int limit) {
        if (days < 1 || days > 30 || limit < 1 || limit > 200) {
            throw ApiException.badRequest("bad_range", "days must be 1..30 and limit 1..200");
        }
        return misses.top(days, limit);
    }

    private static void checkLength(String q) {
        if (q != null && q.length() > 100) {
            throw ApiException.badRequest("query_too_long", "Search text is limited to 100 characters");
        }
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
