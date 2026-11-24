package com.wokflow.api.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DispatchIT extends IntegrationTest {
    static final GeoPoint KITCHEN = new GeoPoint(34.0911, -118.1293); // newKitchen() is at the 91801 centroid

    @Autowired
    Dispatcher dispatcher;
    @Autowired
    RiderPresence presence;
    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    final List<Long> online = new ArrayList<>();

    record Rider(long id, String token) {}

    Rider newRider(GeoPoint at) {
        String email = unique("rider") + "@example.com";
        db.sql("insert into users (email, password_hash, display_name, role) select ?, password_hash, 'Test Rider', 'RIDER' from users where email = 'rider@example.com'")
                .param(email).update();
        String token = signIn(email, "wokflow-demo");
        long id = db.sql("select id from users where email = ?").param(email).query(Long.class).single();
        assertThat(http.post("/api/rider/presence", token, Map.of("online", true, "lat", at.lat(), "lng", at.lng())).status()).isEqualTo(200);
        online.add(id);
        return new Rider(id, token);
    }

    @AfterEach
    void offline() {
        online.forEach(presence::offline);
        clock.reset();
    }

    UUID acceptedOrder(TestKitchen k) {
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1));
        pay(id);
        assertThat(http.post("/api/merchant/orders/" + id + "/accept", signIn(k.merchantEmail()), null).status()).isEqualTo(200);
        return id;
    }

    @Test
    void theNearestFreeRiderIsOfferedTheOrderThenTheNextOneOnDeclineOrTimeout() {
        var k = newKitchen(1);
        Rider near = newRider(KITCHEN.offset(500, 90));
        Rider mid = newRider(KITCHEN.offset(1_500, 180));
        Rider far = newRider(KITCHEN.offset(3_000, 270));
        newRider(KITCHEN.offset(9_000, 0)); // outside the search radius

        UUID order = acceptedOrder(k);
        // the kitchen's accept event triggers dispatch through the outbox
        waitUntil(() -> dispatcher.openOffer(order).isPresent());
        var first = dispatcher.openOffer(order).orElseThrow();
        assertThat(first.riderId()).isEqualTo(near.id());
        assertThat(first.distanceM()).isBetween(450, 550);
        assertThat(http.get("/api/rider/offers/current", near.token()).body().get("orderId").asText()).isEqualTo(order.toString());
        assertThat(http.get("/api/rider/offers/current", mid.token()).status()).isEqualTo(204);

        // declined: straight to the next nearest
        assertThat(http.post("/api/rider/offers/" + first.id() + "/decline", near.token(), null).status()).isEqualTo(200);
        var second = dispatcher.openOffer(order).orElseThrow();
        assertThat(second.riderId()).isEqualTo(mid.id());

        // not answered in time: expires and moves on; the late answer is refused
        clock.advance(Dispatcher.OFFER_TTL.plusSeconds(1));
        dispatcher.expire();
        var third = dispatcher.openOffer(order).orElseThrow();
        assertThat(third.riderId()).isEqualTo(far.id());
        assertThat(http.post("/api/rider/offers/" + second.id() + "/accept", mid.token(), null).code()).isEqualTo("offer_gone");

        // accepted: the order is claimed in the same transaction and leaves the job board
        var claimed = http.post("/api/rider/offers/" + third.id() + "/accept", far.token(), null);
        assertThat(claimed.status()).isEqualTo(200);
        assertThat(claimed.body().get("riderId").asLong()).isEqualTo(far.id());
        assertThat(http.get("/api/rider/jobs", far.token()).body().toString()).doesNotContain(order.toString());
        assertThat(db.sql("select string_agg(status, ',' order by id) from dispatch_offers where order_id = ?").param(order)
                .query(String.class).single()).isEqualTo("DECLINED,EXPIRED,ACCEPTED");
    }

    @Test
    void busyStaleAndPendingRidersAreSkipped() {
        var k = newKitchen(1);
        Rider busy = newRider(KITCHEN.offset(200, 0));
        UUID first = acceptedOrder(k);
        waitUntil(() -> dispatcher.openOffer(first).isPresent());
        http.post("/api/rider/offers/" + dispatcher.openOffer(first).orElseThrow().id() + "/accept", busy.token(), null);

        Rider stale = newRider(KITCHEN.offset(300, 0));
        redis.opsForZSet().add(RiderPresence.SEEN, Long.toString(stale.id()), System.currentTimeMillis() - 120_000);
        Rider free = newRider(KITCHEN.offset(2_000, 0));

        UUID second = acceptedOrder(k);
        waitUntil(() -> dispatcher.openOffer(second).isPresent());
        assertThat(dispatcher.openOffer(second).orElseThrow().riderId()).isEqualTo(free.id()); // not the busy or the silent one
    }

    @Test
    void concurrentDispatchersOfferAnOrderOnlyOnce() throws Exception {
        var k = newKitchen(1);
        for (int i = 0; i < 5; i++) {
            newRider(KITCHEN.offset(300 + i * 100, i * 60));
        }
        UUID order = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1));
        pay(order);
        // move to ACCEPTED without the outbox racing us: dispatch by hand from 8 threads
        db.sql("update orders set status = 'ACCEPTED' where id = ?").param(order).update();
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> dispatcher.dispatch(order).isPresent());
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            pool.invokeAll(calls);
        }
        assertThat(db.sql("select count(*) from dispatch_offers where order_id = ? and status = 'OFFERED'").param(order)
                .query(Long.class).single()).isOne();
    }

    @Test
    void takingAJobFromTheBoardWithdrawsTheOfferAndTheCustomerSeesTheRiderMove() {
        var k = newKitchen(1);
        Rider offered = newRider(KITCHEN.offset(400, 0));
        UUID order = acceptedOrder(k);
        waitUntil(() -> dispatcher.openOffer(order).isPresent());
        String other = signIn("rider2@example.com");
        assertThat(http.post("/api/rider/orders/" + order + "/claim", other, null).status()).isEqualTo(200);
        waitUntil(() -> dispatcher.openOffer(order).isEmpty());
        long offerId = db.sql("select id from dispatch_offers where order_id = ?").param(order).query(Long.class).single();
        assertThat(http.post("/api/rider/offers/" + offerId + "/accept", offered.token(), null).code()).isEqualTo("offer_gone");

        // rider2 heads to the kitchen: the customer's order stream gets distance and ETA
        long rider2 = db.sql("select id from users where email = 'rider2@example.com'").query(Long.class).single();
        online.add(rider2);
        GeoPoint start = KITCHEN.offset(1_800, 45);
        assertThat(http.post("/api/rider/location", other, Map.of("lat", start.lat(), "lng", start.lng())).status()).isEqualTo(200);
        String customer = db.sql("select u.email from orders o join users u on u.id = o.customer_id where o.id = ?").param(order)
                .query(String.class).single();
        var seen = http.get("/api/orders/" + order + "/rider", signIn(customer, "correct-horse-1")).body();
        assertThat(seen.get("heading").asText()).isEqualTo("restaurant");
        assertThat(seen.get("distanceM").asInt()).isBetween(1_750, 1_850);
        assertThat(seen.get("etaMinutes").asInt()).isBetween(6, 7); // 1.8 km at 18 km/h, rounded up
        var entries = redis.opsForStream().reverseRange(com.wokflow.api.live.LiveHub.STREAM, org.springframework.data.domain.Range.unbounded(),
                org.springframework.data.redis.connection.Limit.limit().count(20));
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.getValue().get("type")).isEqualTo("rider");
            assertThat(e.getValue().get("keys")).isEqualTo("order:" + order);
        });
        assertThat(http.post("/api/rider/location", other, Map.of("lat", 40.7, "lng", -74.0)).code()).isEqualTo("outside_region");
        assertThat(http.get("/api/orders/" + order + "/rider", newCustomer()).status()).isEqualTo(404);
    }
}
