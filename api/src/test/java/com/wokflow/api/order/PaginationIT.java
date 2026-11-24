package com.wokflow.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaginationIT extends IntegrationTest {

    List<String> readAll(String path, String token, int pageSize, Runnable betweenPages) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            String sep = path.contains("?") ? "&" : "?";
            JsonNode page = http.get(path + sep + "limit=" + pageSize + (cursor == null ? "" : "&cursor=" + cursor), token).body();
            page.get("items").forEach(o -> ids.add(o.get("id").asText()));
            cursor = page.has("next") ? page.get("next").asText() : null;
            betweenPages.run();
        } while (cursor != null);
        return ids;
    }

    @Test
    void keysetPagesAreCompleteAndStableWhileNewOrdersArrive() {
        var k = newKitchen(1);
        String c = newCustomer();
        List<UUID> placed = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            placed.add(placeOrder(c, k.id(), Map.of(k.plainDish(), 1)));
        }
        // new orders placed between page reads go to the top: they never shift what the reader has not seen yet
        List<String> seen = readAll("/api/orders", c, 10, () -> placeOrder(c, k.id(), Map.of(k.plainDish(), 1)));
        assertThat(seen).hasSize(23).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(placed.reversed().stream().map(UUID::toString).toList());

        // the kitchen's history, filtered by status
        String m = signIn(k.merchantEmail());
        deliver(placed.getFirst(), k);
        var delivered = http.get("/api/merchant/restaurants/" + k.id() + "/orders/history?status=DELIVERED", m).body();
        assertThat(delivered.get("items")).hasSize(1);
        assertThat(delivered.get("items").get(0).get("id").asText()).isEqualTo(placed.getFirst().toString());
        assertThat(readAll("/api/merchant/restaurants/" + k.id() + "/orders/history", m, 7, () -> { })).hasSize(26);
        assertThat(http.get("/api/merchant/restaurants/" + k.id() + "/orders/history?status=EATEN", m).code()).isEqualTo("bad_status");
        assertThat(http.get("/api/merchant/restaurants/" + k.id() + "/orders/history", signIn("partners@example.com")).status()).isEqualTo(403);

        // the rider sees the delivery in history and nothing active
        String rider = signIn("rider@example.com");
        assertThat(http.get("/api/rider/orders/history?limit=50", rider).body().toString()).contains(placed.getFirst().toString());
        assertThat(http.get("/api/rider/orders", rider).body().toString()).doesNotContain(placed.getFirst().toString());

        // admins page across the platform; others cannot
        String admin = signIn("admin@example.com");
        assertThat(http.get("/api/admin/orders?status=DELIVERED&limit=5", admin).body().get("items").size()).isBetween(1, 5);
        assertThat(http.get("/api/admin/orders", c).status()).isEqualTo(403);
        assertThat(http.get("/api/orders?cursor=not-a-cursor", c).code()).isEqualTo("bad_cursor");
        assertThat(http.get("/api/orders?limit=500", c).body().get("items").size()).isEqualTo(26); // limit capped at 50, 26 exist
    }
}
