package com.wokflow.api.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** "Often ordered together" in the cart and "Order again" on the home page. */
class RecommendIT extends IntegrationTest {
    @Autowired
    Pairings pairings;

    @Test
    void oftenOrderedTogetherAndOrderAgain() {
        TestKitchen k = newKitchen(100);
        long tea = db.sql("insert into dishes (restaurant_id, category_id, name, name_zh, price_cents) "
                + "select ?, category_id, 'Milk Tea', '奶茶', 500 from dishes where id = ? returning id")
                .params(k.id(), k.plainDish()).query(Long.class).single();
        String alice = newCustomer();
        String bob = newCustomer();
        // fried rice + dumplings three times, fried rice + tea once
        for (String who : List.of(alice, bob, alice)) {
            pay(placeOrder(who, k.id(), Map.of(k.plainDish(), 1, k.limitedDish(), 1)));
        }
        pay(placeOrder(bob, k.id(), Map.of(k.plainDish(), 1, tea, 1)));
        placeOrder(alice, k.id(), Map.of(tea, 2)); // never paid: does not count

        assertThat(pairings.rebuild()).isPositive();
        var withRice = http.get("/api/restaurants/" + k.slug() + "/pairings?dish=" + k.plainDish(), null);
        assertThat(withRice.status()).isEqualTo(200);
        assertThat(withRice.body()).hasSize(1); // tea was ordered with rice only once: below the minimum
        assertThat(withRice.body().get(0).get("dishId").asLong()).isEqualTo(k.limitedDish());
        assertThat(withRice.body().get(0).get("together").asInt()).isEqualTo(3);
        // what is already in the cart is never suggested
        assertThat(http.get("/api/restaurants/" + k.slug() + "/pairings?dish=" + k.plainDish() + "&dish=" + k.limitedDish(), null)
                .body()).isEmpty();
        assertThat(http.get("/api/restaurants/no-such-place/pairings?dish=1", null).status()).isEqualTo(404);

        // an archived dish is never suggested, even with history
        db.sql("update dishes set archived_at = now() where id = ?").param(k.limitedDish()).update();
        assertThat(http.get("/api/restaurants/" + k.slug() + "/pairings?dish=" + k.plainDish(), null).body()).isEmpty();
        db.sql("update dishes set archived_at = null where id = ?").param(k.limitedDish()).update();

        var again = http.get("/api/me/reorder", alice).body();
        List<Long> dishes = new ArrayList<>();
        again.forEach(d -> dishes.add(d.get("dishId").asLong()));
        assertThat(dishes).containsExactlyInAnyOrder(k.plainDish(), k.limitedDish()); // not the unpaid tea
        again.forEach(d -> assertThat(d.get("times").asInt()).isEqualTo(2));
        // too far away: nothing to order again from there
        assertThat(http.get("/api/me/reorder?lat=40.7128&lng=-74.0060", alice).body()).isEmpty();
        assertThat(http.get("/api/me/reorder", signIn("merchant@example.com")).body()).isEmpty();
        assertThat(http.get("/api/me/reorder", null).status()).isEqualTo(401);
    }

    @Test
    void aSecondRebuildAtTheSameTimeStepsAside() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(pairings::rebuild);
            var b = pool.submit(pairings::rebuild);
            List<Integer> results = List.of(a.get(), b.get());
            // either they ran one after the other, or one found the lock taken and returned -1; never both at once
            assertThat(results).allSatisfy(r -> assertThat(r).isGreaterThanOrEqualTo(-1));
        } finally {
            pool.shutdown();
        }
    }
}
