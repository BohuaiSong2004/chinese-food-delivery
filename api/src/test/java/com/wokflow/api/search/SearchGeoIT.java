package com.wokflow.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;

class SearchGeoIT extends IntegrationTest {
    @Test
    void searchOnlyReturnsRestaurantsThatDeliverToThePoint() throws Exception {
        String rowland = "lat=33.9804&lng=-117.8986";
        String alhambra = "lat=34.0911&lng=-118.1293";
        waitUntil(() -> http.get("/api/search?q=biang&" + rowland, null).body().get("total").asInt() > 0);
        var near = http.get("/api/search?q=biang&" + rowland, null).body();
        assertThat(near.get("degraded").asBoolean()).isFalse(); // answered by Elasticsearch
        assertThat(near.get("hits").get(0).get("restaurantSlug").asText()).isEqualTo("xian-spice-road");
        assertThat(near.get("hits").get(0).get("distanceM").asInt()).isLessThan(12_000);
        assertThat(http.get("/api/search?q=biang&" + alhambra, null).body().get("total").asInt()).isZero();
        // dumplings from Alhambra kitchens are not offered in Rowland Heights
        assertThat(http.get("/api/search?q=dumplings&" + rowland, null).body().toString()).doesNotContain("lucky-star-dumplings");

        // the Postgres fallback applies the same rule
        var svc = searchService;
        var degraded = svc.postgres(new SearchService.Criteria("biang", java.util.List.of(),
                java.util.List.of(), null, 0, 20, new GeoPoint(34.0911, -118.1293)));
        assertThat(degraded.hits()).isEmpty();
        assertThat(svc.postgres(new SearchService.Criteria("biang", java.util.List.of(),
                java.util.List.of(), null, 0, 20, new GeoPoint(33.9804, -117.8986))).hits()).isNotEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired
    SearchService searchService;
}
