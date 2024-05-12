package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.ledger.LedgerAudit;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.payment.RefundWorker;
import com.wokflow.api.support.FakePaymentProvider;
import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderFlowIT extends IntegrationTest {
    @Autowired
    LedgerService ledger;
    @Autowired
    LedgerAudit audit;
    @Autowired
    RefundWorker refunds;
    @Autowired
    PaymentDeadlineJob deadlines;

    @Test
    void happyPathFromCartToDoorstepPostsTheRightLedgerEntries() {
        var k = newKitchen(10);
        String customer = newCustomer();
        String merchant = signIn(k.merchantEmail());
        String rider = signIn("rider@example.com");

        var placed = placeOrder(customer, k.id(), Map.of(k.plainDish(), 2, k.limitedDish(), 1), unique("key-"));
        assertThat(placed.status()).isEqualTo(201);
        UUID id = UUID.fromString(placed.body().get("id").asText());
        assertThat(placed.body().get("status").asText()).isEqualTo("PENDING_PAYMENT");
        assertThat(placed.body().get("subtotalCents").asInt()).isEqualTo(2 * 1000 + 1500);
        assertThat(placed.body().get("totalCents").asInt()).isEqualTo(3500 + 300);
        assertThat(placed.body().get("checkoutUrl").asText()).isEqualTo("https://pay.test/checkout/" + FakePaymentProvider.intentId(id));
        assertThat(stock(k.limitedDish())).isEqualTo(9);

        assertThat(pay(id).body().get("result").asText()).isEqualTo("processed");
        assertThat(status(id)).isEqualTo("PAID");

        assertThat(http.post("/api/merchant/orders/" + id + "/accept", merchant, null).status()).isEqualTo(200);
        assertThat(http.post("/api/merchant/orders/" + id + "/ready", merchant, null).status()).isEqualTo(200);
        assertThat(http.post("/api/rider/orders/" + id + "/claim", rider, null).status()).isEqualTo(200);
        assertThat(http.post("/api/rider/orders/" + id + "/pickup", rider, null).status()).isEqualTo(200);
        var done = http.post("/api/rider/orders/" + id + "/deliver", rider, null);
        assertThat(done.body().get("status").asText()).isEqualTo("DELIVERED");
        assertThat(done.body().get("version").asInt()).isEqualTo(7);
        assertThat(done.body().get("history")).hasSize(7);

        // commission 15% of 35.00 = 5.25 -> merchant gets 29.75
        assertThat(ledger.merchantBalance(k.id())).isEqualTo(2975);
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }

    @Test
    void idempotencyKeyReturnsTheSameOrderAndRefusesADifferentBody() {
        var k = newKitchen(10);
        String customer = newCustomer();
        String key = unique("key-");
        var first = placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2), key);
        var again = placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2), key);
        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body().get("id")).isEqualTo(first.body().get("id"));
        assertThat(stock(k.limitedDish())).isEqualTo(8); // reserved once

        assertThat(placeOrder(customer, k.id(), Map.of(k.limitedDish(), 3), key).code()).isEqualTo("idempotency_key_reused");
        var noKey = http.post("/api/orders", customer, Map.of("restaurantId", k.id(),
                "items", java.util.List.of(Map.of("dishId", k.plainDish(), "quantity", 1)), "addressId", addressId(customer)));
        assertThat(noKey.code()).isEqualTo("idempotency_key_required");
    }

    @Test
    void ordersAreValidatedAgainstTheDatabaseNotTheClient() {
        var k = newKitchen(1);
        var other = newKitchen(5);
        String customer = newCustomer();
        assertThat(placeOrder(customer, k.id(), Map.of(other.plainDish(), 1), unique("k")).code()).isEqualTo("unknown_dish");
        assertThat(placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2), unique("k")).code()).isEqualTo("sold_out");
        assertThat(stock(k.limitedDish())).isEqualTo(1); // rolled back
        db.sql("update dishes set available = false where id = ?").param(k.plainDish()).update();
        assertThat(placeOrder(customer, k.id(), Map.of(k.plainDish(), 1), unique("k")).code()).isEqualTo("dish_unavailable");
        assertThat(placeOrder(signIn("merchant@example.com"), k.id(), Map.of(k.limitedDish(), 1), unique("k")).code())
                .isEqualTo("customers_only");
        db.sql("update restaurants set accepting_orders = false where id = ?").param(k.id()).update();
        assertThat(placeOrder(customer, k.id(), Map.of(k.limitedDish(), 1), unique("k")).code()).isEqualTo("restaurant_paused");
    }

    @Test
    void closedRestaurantsRefuseOrdersInTheirOwnTimeZone() {
        long rid = restaurantId("golden-harbor-dim-sum"); // 09:00-15:00 America/Los_Angeles
        long dish = dishId("golden-harbor-dim-sum", "Egg Tarts");
        String customer = newCustomer();
        ZonedDateTime la = ZonedDateTime.now(ZoneId.of("America/Los_Angeles"));
        clock.setTo(la.withHour(20).withMinute(0).toInstant());
        var r = placeOrder(customer, rid, Map.of(dish, 4), unique("k"));
        assertThat(r.code()).isEqualTo("restaurant_closed");
        clock.setTo(la.withHour(10).withMinute(30).toInstant());
        assertThat(placeOrder(customer, rid, Map.of(dish, 4), unique("k")).status()).isEqualTo(201);
    }

    @Test
    void webhooksAreVerifiedAndProcessedExactlyOnce() {
        var k = newKitchen(10);
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1));
        String intent = FakePaymentProvider.intentId(id);
        long now = clock.instant().getEpochSecond();

        assertThat(webhook("evt_bad", "payment_intent.succeeded", intent, now, "wrong-secret").code()).isEqualTo("bad_signature");
        assertThat(webhook("evt_old", "payment_intent.succeeded", intent, now - 3600, WEBHOOK_SECRET).code()).isEqualTo("bad_signature");
        assertThat(http.raw("POST", "/api/payments/webhook", "{}".getBytes(), Map.of("Content-Type", "application/json")).status())
                .isEqualTo(400);
        assertThat(status(id)).isEqualTo("PENDING_PAYMENT");

        String evt = unique("evt_");
        assertThat(webhook(evt, "payment_intent.succeeded", intent, now, WEBHOOK_SECRET).body().get("result").asText()).isEqualTo("processed");
        assertThat(webhook(evt, "payment_intent.succeeded", intent, now, WEBHOOK_SECRET).body().get("result").asText()).isEqualTo("duplicate");
        // a different event for the same payment (provider retried under a new id) must not capture twice
        webhook(unique("evt_"), "payment_intent.succeeded", intent, now, WEBHOOK_SECRET);
        assertThat(captures(id)).isEqualTo(1);
    }

    @Test
    void rejectionRefundsThroughTheWorkerAndReleasesStock() {
        var k = newKitchen(10);
        String merchant = signIn(k.merchantEmail());
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.limitedDish(), 3));
        pay(id);
        provider.failRefunds.set(true);
        var rejected = http.post("/api/merchant/orders/" + id + "/reject", merchant, Map.of("reason", "out of flour"));
        assertThat(rejected.body().get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.body().get("cancelReason").asText()).isEqualTo("out of flour");
        assertThat(stock(k.limitedDish())).isEqualTo(10);

        refunds.runOnce();
        assertThat(refundStatus(id)).isEqualTo("REQUESTED"); // provider down: kept for retry
        // while the refund is pending the merchant cannot pay that money out
        assertThat(ledger.balance(k.id()).availableCents()).isZero();

        provider.failRefunds.set(false);
        db.sql("update refunds set next_attempt_at = now() where order_id = ?").param(id).update();
        refunds.runOnce();
        assertThat(refundStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(ledger.merchantBalance(k.id())).isZero();
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }

    @Test
    void unpaidOrdersExpireAndLatePaymentsAreRefunded() {
        var k = newKitchen(5);
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.limitedDish(), 2));
        assertThat(stock(k.limitedDish())).isEqualTo(3);
        clock.advance(Duration.ofMinutes(16));
        deadlines.run();
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(stock(k.limitedDish())).isEqualTo(5);

        // the customer paid at the last second and the webhook arrives after the cancellation
        clock.reset();
        assertThat(pay(id).body().get("result").asText()).isEqualTo("processed");
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(captures(id)).isEqualTo(1);
        refunds.runOnce();
        assertThat(refundStatus(id)).isEqualTo("SUCCEEDED");
        assertThat(LedgerAudit.consistent(audit.run())).isTrue();
    }

    @Test
    void declinedPaymentCancelsTheOrder() {
        var k = newKitchen(5);
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.limitedDish(), 1));
        webhook(unique("evt_"), "payment_intent.payment_failed", id);
        assertThat(status(id)).isEqualTo("CANCELLED");
        assertThat(db.sql("select cancel_reason from orders where id = ?").param(id).query(String.class).single())
                .isEqualTo("payment_declined");
        assertThat(stock(k.limitedDish())).isEqualTo(5);
    }

    @Test
    void providerOutageAtCheckoutCanBeRetried() {
        var k = newKitchen(5);
        String customer = newCustomer();
        provider.failIntents.set(true);
        var placed = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1), unique("k"));
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.body().get("checkoutUrl")).isNull();
        String id = placed.body().get("id").asText();
        assertThat(http.post("/api/orders/" + id + "/checkout", customer, null).code()).isEqualTo("payments_unavailable");
        provider.failIntents.set(false);
        assertThat(http.post("/api/orders/" + id + "/checkout", customer, null).body().get("checkoutUrl").asText())
                .startsWith("https://pay.test/checkout/");
    }

    @Test
    void staleAndInvalidTransitionsAreRefused() {
        var k = newKitchen(5);
        String customer = newCustomer();
        String merchant = signIn(k.merchantEmail());
        UUID id = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1));
        assertThat(http.post("/api/merchant/orders/" + id + "/accept", merchant, null).code()).isEqualTo("invalid_transition");
        pay(id); // version 2
        var stale = http.post("/api/merchant/orders/" + id + "/accept", merchant, null, Map.of("If-Match", "\"1\""));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("stale_version");
        assertThat(http.post("/api/merchant/orders/" + id + "/accept", merchant, null, Map.of("If-Match", "\"2\"")).status())
                .isEqualTo(200);
        // once accepted the customer can no longer cancel
        assertThat(http.post("/api/orders/" + id + "/cancel", customer, null).code()).isEqualTo("invalid_transition");
    }

    /** An approved rider (copies the seeded rider's password hash). */
    String newRider() {
        String email = unique("rider") + "@example.com";
        db.sql("insert into users (email, password_hash, display_name, role) select ?, password_hash, 'Test Rider', 'RIDER' from users where email = 'rider@example.com'")
                .param(email).update();
        return signIn(email);
    }

    @Test
    void aRetriedChangeWhoseResponseWasLostSucceedsButSomeoneElsesStillConflicts() {
        var k = newKitchen(5);
        String merchant = signIn(k.merchantEmail());
        UUID id = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 1));
        pay(id); // version 2
        var first = http.post("/api/merchant/orders/" + id + "/accept", merchant, null, Map.of("If-Match", "\"2\""));
        assertThat(first.body().get("version").asInt()).isEqualTo(3);
        // the same request again, as a client does after a timeout or a 503 from the proxy
        var retry = http.post("/api/merchant/orders/" + id + "/accept", merchant, null, Map.of("If-Match", "\"2\""));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body().get("status").asText()).isEqualTo("ACCEPTED");
        assertThat(retry.body().get("version").asInt()).isEqualTo(3);
        assertThat(db.sql("select count(*) from order_status_history where order_id = ?").param(id).query(Long.class).single())
                .as("nothing applied twice").isEqualTo(3);

        // a different action from the same stale version is still a conflict
        assertThat(http.post("/api/merchant/orders/" + id + "/ready", merchant, null, Map.of("If-Match", "\"2\"")).code())
                .isEqualTo("stale_version");
        // and so is someone else's: two riders saw version 4; the second must not be told it won
        http.post("/api/merchant/orders/" + id + "/ready", merchant, null, Map.of("If-Match", "\"3\""));
        String riderA = newRider();
        String riderB = newRider();
        assertThat(http.post("/api/rider/orders/" + id + "/claim", riderA, null, Map.of("If-Match", "\"4\"")).status()).isEqualTo(200);
        assertThat(http.post("/api/rider/orders/" + id + "/claim", riderA, null, Map.of("If-Match", "\"4\"")).status()).isEqualTo(200);
        assertThat(http.post("/api/rider/orders/" + id + "/claim", riderB, null, Map.of("If-Match", "\"4\"")).code()).isEqualTo("stale_version");
    }

    @Test
    void customersCancelBeforeTheKitchenAcceptsAndGetRefunded() {
        var k = newKitchen(5);
        String customer = newCustomer();
        UUID id = placeOrder(customer, k.id(), Map.of(k.limitedDish(), 2));
        pay(id);
        assertThat(http.post("/api/orders/" + id + "/cancel", customer, null).body().get("status").asText()).isEqualTo("CANCELLED");
        assertThat(refundStatus(id)).isEqualTo("REQUESTED");
        assertThat(stock(k.limitedDish())).isEqualTo(5);
    }

    @Test
    void accessIsScopedToTheOrdersParticipants() {
        var k = newKitchen(5);
        var other = newKitchen(5);
        String owner = newCustomer();
        UUID id = placeOrder(owner, k.id(), Map.of(k.plainDish(), 1));
        assertThat(http.get("/api/orders/" + id, owner).status()).isEqualTo(200);
        assertThat(http.get("/api/orders/" + id, newCustomer()).status()).isEqualTo(404);
        assertThat(http.post("/api/orders/" + id + "/cancel", newCustomer(), null).status()).isEqualTo(404);
        pay(id);
        String otherMerchant = signIn(other.merchantEmail());
        assertThat(http.post("/api/merchant/orders/" + id + "/accept", otherMerchant, null).status()).isEqualTo(403);
        assertThat(http.get("/api/merchant/restaurants/" + k.id() + "/orders", otherMerchant).status()).isEqualTo(403);
        assertThat(http.post("/api/rider/orders/" + id + "/claim", owner, null).status()).isEqualTo(403);

        String merchant = signIn(k.merchantEmail());
        http.post("/api/merchant/orders/" + id + "/accept", merchant, null);
        http.post("/api/merchant/orders/" + id + "/ready", merchant, null);
        String rider = signIn("rider@example.com");
        String rider2 = signIn("rider2@example.com");
        assertThat(http.get("/api/orders/" + id, rider2).status()).isEqualTo(200); // open job is visible
        http.post("/api/rider/orders/" + id + "/claim", rider, null);
        assertThat(http.post("/api/rider/orders/" + id + "/claim", rider2, null).code()).isEqualTo("already_claimed");
        assertThat(http.post("/api/rider/orders/" + id + "/pickup", rider2, null).status()).isEqualTo(403);
        assertThat(http.get("/api/orders/" + id, rider2).status()).isEqualTo(404);
    }

    @Test
    void merchantBoardListsPaidOrdersButNotUnpaidOnes() {
        var k = newKitchen(5);
        String customer = newCustomer();
        UUID paid = placeOrder(customer, k.id(), Map.of(k.plainDish(), 1));
        UUID unpaid = placeOrder(customer, k.id(), Map.of(k.plainDish(), 2));
        pay(paid);
        var board = http.get("/api/merchant/restaurants/" + k.id() + "/orders", signIn(k.merchantEmail())).body();
        assertThat(board).hasSize(1);
        assertThat(board.get(0).get("id").asText()).isEqualTo(paid.toString());
        assertThat(board.get(0).get("customerName").asText()).isEqualTo("Test Customer");
        assertThat(unpaid).isNotNull();
    }

    private int stock(long dishId) {
        return db.sql("select stock_remaining from dishes where id = ?").param(dishId).query(Integer.class).single();
    }

    private int captures(UUID id) {
        return db.sql("select count(*) from ledger_entries where kind = 'CAPTURE' and order_id = ?").param(id).query(Integer.class).single();
    }

    private String refundStatus(UUID id) {
        return db.sql("select status from refunds where order_id = ?").param(id).query(String.class).single();
    }

    @SuppressWarnings("unused")
    private static Instant now() { return Instant.now(); }
}
