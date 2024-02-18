package com.wokflow.api.hot;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class HotMenuIT extends IntegrationTest {
    @Autowired
    HotMenuService hot;
    @Autowired
    StringRedisTemplate redis;

    @Test
    void paidOrdersRankDishesAndMatchTheSqlAnswerAfterARedisWipe() throws Exception {
        var k = newKitchen(1000);
        String customer = newCustomer();
        for (int i = 0; i < 3; i++) {
            pay(placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2, k.plainDish(), 1)));
        }
        pay(placeOrder(customer, k.id(), Map.of(k.plainDish(), 5)));
        placeOrder(customer, k.id(), Map.of(k.limitedDish(), 9)); // never paid: does not count
        assertThat(outbox.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Map<Long, Long> expected = new LinkedHashMap<>();
        expected.put(k.plainDish(), 8L);   // highest first
        expected.put(k.limitedDish(), 6L);
        assertThat(ranked(k.id())).containsExactlyEntriesOf(expected);
        assertThat(hot.topFromSql(k.id(), 10)).containsExactlyEntriesOf(expected);

        var menu = http.get("/api/restaurants/" + k.slug(), null).body();
        assertThat(menu.get("popular").get(0).get("dishId").asLong()).isEqualTo(k.plainDish());
        assertThat(menu.get("popular").get(0).get("orders").asLong()).isEqualTo(8);

        // Redis restarted empty: the counters come back from the ledger, without double counting
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        hot.rebuildIfLost();
        assertThat(ranked(k.id())).containsExactlyEntriesOf(expected);
        hot.rebuildIfLost(); // marker present: no-op
        assertThat(ranked(k.id())).containsExactlyEntriesOf(expected);
    }

    @Test
    void eachOrderIsCountedOnce() {
        UUID order = UUID.randomUUID();
        long rid = 987_654_321L;
        assertThat(hot.record(order, rid, Map.of(1L, 2), clock.instant())).isTrue();
        assertThat(hot.record(order, rid, Map.of(1L, 2), clock.instant())).isFalse();
        assertThat(hot.record(UUID.randomUUID(), rid, Map.of(1L, 1), clock.instant().minus(Duration.ofHours(30)))).isFalse();
        assertThat(ranked(rid)).containsEntry(1L, 2L);
    }

    @Test
    void trendingSpansRestaurants() {
        var k = newKitchen(1000);
        pay(placeOrder(newCustomer(), k.id(), Map.of(k.limitedDish(), 40)));
        assertThat(outbox.awaitIdle(Duration.ofSeconds(10))).isTrue();
        redis.keys(HotMenuService.PREFIX + "all:top*").forEach(redis::delete); // skip the 15 s result cache
        var trending = http.get("/api/trending?limit=20", null).body();
        var mine = new java.util.ArrayList<tools.jackson.databind.JsonNode>();
        java.util.Set<String> restaurants = new java.util.HashSet<>();
        trending.forEach(t -> {
            restaurants.add(t.get("restaurantSlug").asText());
            if (t.get("dishId").asLong() == k.limitedDish()) {
                mine.add(t);
            }
        });
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().get("orders").asLong()).isEqualTo(40);
        assertThat(mine.getFirst().get("restaurantSlug").asText()).isEqualTo(k.slug());
        assertThat(restaurants.size()).isGreaterThan(1);
    }

    private Map<Long, Long> ranked(long restaurantId) {
        Map<Long, Long> out = new LinkedHashMap<>();
        // bypass the 15 s result cache so the assertion sees the current counters
        redis.keys(HotMenuService.PREFIX + "r:" + restaurantId + ":top*").forEach(redis::delete);
        hot.ranked(HotMenuService.PREFIX + "r:" + restaurantId + ":", 10).forEach((k, v) -> out.put(Long.parseLong(k), v));
        return out;
    }
}
