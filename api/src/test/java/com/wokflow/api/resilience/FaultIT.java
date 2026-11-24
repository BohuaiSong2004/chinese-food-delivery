package com.wokflow.api.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.Http;
import com.wokflow.api.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * What a customer sees while one dependency hangs, and that everything recovers afterwards.
 * Each dependency is frozen with docker pause, so clients run into their timeouts instead of a
 * quick "connection refused". Timings are printed for docs/engineering-notes.md.
 */
class FaultIT extends IntegrationTest {

    record Timed<T>(T value, long millis) {}

    static <T> Timed<T> timed(Supplier<T> s) {
        long t = System.nanoTime();
        T v = s.get();
        return new Timed<>(v, (System.nanoTime() - t) / 1_000_000);
    }

    long unpublished() {
        return db.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
    }

    @Test
    void searchAnswersFromPostgresWhileElasticsearchHangsAndReturnsToElasticsearch() throws Exception {
        newKitchen(10);
        whileFrozen(Dependency.ELASTICSEARCH, () -> {
            for (int i = 0; i < 3; i++) {
                var r = timed(() -> http.get("/api/search?q=Fried%20Rice", null));
                System.out.printf("[fault] search, Elasticsearch frozen, #%d: %d ms%n", i + 1, r.millis());
                assertThat(r.value().status()).isEqualTo(200);
                assertThat(r.value().body().get("degraded").asBoolean()).isTrue();
                assertThat(r.value().body().get("total").asLong()).isPositive();
                assertThat(r.millis()).isLessThan(3000);
            }
        });
        waitUntil(() -> !http.get("/api/search?q=Fried%20Rice", null).body().get("degraded").asBoolean());
    }

    @Test
    void ordersMenusAndSignInKeepWorkingWhileRedisHangs() throws Exception {
        var k = newKitchen(10);
        String customer = newCustomer();
        whileFrozen(Dependency.REDIS, () -> {
            var menu1 = timed(() -> http.get("/api/restaurants/" + k.slug(), null));
            var menu2 = timed(() -> http.get("/api/restaurants/" + k.slug(), null));
            var place = timed(() -> placeOrder(customer, k.id(), Map.of(k.plainDish(), 2), unique("k")));
            var pay = timed(() -> pay(UUID.fromString(place.value().body().get("id").asText())));
            var signIn = timed(() -> http.post("/api/auth/signin", null, Map.of("email", k.merchantEmail(), "password", "wokflow-demo")));
            System.out.printf("[fault] Redis frozen: first menu %d ms, second menu %d ms, place order %d ms, pay %d ms, sign in %d ms%n",
                    menu1.millis(), menu2.millis(), place.millis(), pay.millis(), signIn.millis());
            assertThat(menu1.value().status()).isEqualTo(200);
            assertThat(menu2.value().status()).isEqualTo(200);
            assertThat(place.value().status()).isEqualTo(201);
            assertThat(pay.value().status()).isEqualTo(200);
            assertThat(signIn.value().status()).isEqualTo(200);
            // only the first optional Redis call waits out the 2 s timeout; the breaker skips the rest
            assertThat(menu1.millis()).isLessThan(3000);
            assertThat(menu2.millis() + place.millis() + pay.millis() + signIn.millis()).isLessThan(2500);
        });
    }

    @Test
    void liveEventsQueuedWhileRedisHangsAreDeliveredAfterwards() throws Exception {
        var k = newKitchen(10);
        String customer = newCustomer();
        UUID order = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1));
        outbox.awaitIdle(java.time.Duration.ofSeconds(10));
        whileFrozen(Dependency.REDIS, () -> {
            assertThat(pay(order).status()).isEqualTo(200);
            Thread.sleep(3000);
            assertThat(unpublished()).as("the status event waits in the outbox").isPositive();
        });
        long t = System.nanoTime();
        waitUntil(() -> unpublished() == 0);
        System.out.printf("[fault] outbox drained %d ms after Redis came back%n", (System.nanoTime() - t) / 1_000_000);
        assertThat(status(order)).isEqualTo("PAID");
    }

    long unpublished(String topic) {
        return db.sql("select count(*) from outbox where published_at is null and topic = ?").param(topic).query(Long.class).single();
    }

    @Test
    void eventsForHealthyStoresAreNotStuckBehindAFrozenOne() throws Exception {
        var k = newKitchen(10);
        String merchant = signIn(k.merchantEmail());
        java.util.List<UUID> orders = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            orders.add(placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1)));
        }
        outbox.awaitIdle(java.time.Duration.ofSeconds(20));
        whileFrozen(Dependency.REDIS, () -> {
            orders.forEach(this::pay);                       // 20 status events for the live stream (Redis)
            long version = db.sql("select version from dishes where id = ?").param(k.plainDish()).query(Long.class).single();
            assertThat(http.patch("/api/merchant/dishes/" + k.plainDish(), merchant,
                    Map.of("priceCents", 1100, "available", true, "version", version)).status()).isEqualTo(200);
            long t = System.nanoTime();
            waitUntil(() -> unpublished(com.wokflow.api.outbox.Outbox.DISH_CHANGED) == 0); // the search index (Elasticsearch)
            System.out.printf("[fault] dish change indexed %d ms later while %d live events wait for Redis%n",
                    (System.nanoTime() - t) / 1_000_000, unpublished(com.wokflow.api.outbox.Outbox.ORDER_STATUS));
            assertThat(unpublished(com.wokflow.api.outbox.Outbox.ORDER_STATUS)).isPositive();
        });
        waitUntil(() -> unpublished() == 0);
    }

    @Test
    void requestsFailFastWhilePostgresHangsAndTheApiRecovers() throws Exception {
        var k = newKitchen(10);
        whileFrozen(Dependency.POSTGRES, () -> {
            Timed<Http.Res> menu = timed(() -> http.get("/api/restaurants/" + k.slug(), null));
            Timed<Http.Res> ready = timed(() -> http.get("/actuator/health/readiness", null));
            Timed<Http.Res> live = timed(() -> http.get("/actuator/health/liveness", null));
            System.out.printf("[fault] Postgres frozen: menu %d ms -> %d, readiness %d ms -> %d, liveness -> %d%n",
                    menu.millis(), menu.value().status(), ready.millis(), ready.value().status(), live.value().status());
            assertThat(menu.value().status()).isEqualTo(503);
            assertThat(menu.value().header("retry-after")).isNotNull();
            assertThat(menu.millis()).isLessThan(11_000);
            assertThat(ready.value().status()).as("taken out of the load balancer").isEqualTo(503);
            assertThat(live.value().status()).as("but not restarted").isEqualTo(200);
        });
        waitUntil(() -> http.get("/actuator/health/readiness", null).status() == 200);
        assertThat(placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1))).isNotNull();
    }
}
