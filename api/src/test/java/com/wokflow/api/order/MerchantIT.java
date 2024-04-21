package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MerchantIT extends IntegrationTest {
    @Test
    void merchantConsoleMenuPauseEarningsAndPayout() {
        var k = newKitchen(20);
        String merchant = signIn(k.merchantEmail());
        assertThat(http.get("/api/merchant/restaurants", merchant).body()).hasSize(1);
        var menu = http.get("/api/merchant/restaurants/" + k.id() + "/menu", merchant).body();
        assertThat(menu.get("dishes")).hasSize(2);

        // changing daily stock keeps what has already been sold
        String customer = newCustomer();
        UUID id = placeOrder(customer, k.id(), Map.of(k.limitedDish(), 5));
        long version = db.sql("select version from dishes where id = ?").param(k.limitedDish()).query(Long.class).single();
        var updated = http.patch("/api/merchant/dishes/" + k.limitedDish(), merchant,
                Map.of("priceCents", 1600, "available", true, "dailyStock", 30, "version", version)).body();
        assertThat(updated.get("stockRemaining").asInt()).isEqualTo(25);
        assertThat(updated.get("priceCents").asInt()).isEqualTo(1600);
        // the public (cached) menu shows the new price immediately: the cache key carries the menu version
        var pub = http.get("/api/restaurants/" + k.slug(), null).body();
        assertThat(pub.toString()).contains("\"priceCents\":1600");

        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/pause", merchant, null).body().get("acceptingOrders").asBoolean()).isFalse();
        assertThat(placeOrder(customer, k.id(), Map.of(k.plainDish(), 1), unique("k")).code()).isEqualTo("restaurant_paused");
        http.post("/api/merchant/restaurants/" + k.id() + "/resume", merchant, null);

        pay(id);
        var earnings = http.get("/api/merchant/restaurants/" + k.id() + "/ledger", merchant).body();
        assertThat(earnings.get("balance").get("balanceCents").asLong()).isEqualTo(6375); // 75.00 - 15%
        assertThat(earnings.get("entries").get(0).get("kind").asText()).isEqualTo("CAPTURE");
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/payouts", merchant, Map.of("amountCents", 0)).status()).isEqualTo(400);
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/payouts", merchant, Map.of("amountCents", 6376)).code())
                .isEqualTo("insufficient_balance");
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/payouts", merchant, Map.of("amountCents", 6375)).status()).isEqualTo(201);

        String stranger = signIn("partners@example.com");
        assertThat(http.get("/api/merchant/restaurants/" + k.id() + "/ledger", stranger).status()).isEqualTo(403);
        assertThat(http.patch("/api/merchant/dishes/" + k.plainDish(), stranger, Map.of("priceCents", 1, "available", true, "version", 1)).status())
                .isEqualTo(403);
    }

    @Test
    void adminSeesAuditAndStats() {
        String admin = signIn("admin@example.com");
        var audit = http.get("/api/admin/ledger/audit", admin).body();
        assertThat(audit.get("unbalancedEntries").asLong()).isZero();
        var stats = http.get("/api/admin/stats", admin).body();
        assertThat(stats.has("deadlocks")).isTrue();
        assertThat(http.post("/api/admin/search/reindex", admin, null).status()).isEqualTo(202);
    }
}
