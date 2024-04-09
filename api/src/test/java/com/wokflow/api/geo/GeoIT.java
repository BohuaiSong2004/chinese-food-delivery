package com.wokflow.api.geo;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GeoIT extends IntegrationTest {

    @Test
    void addressBook() {
        String c = newCustomer();
        assertThat(http.get("/api/me/addresses", c).body()).isEmpty();
        var a = http.post("/api/me/addresses", c, Map.of("label", "Work", "line1", "55 S Lake Ave", "line2", "Suite 200",
                "zip", "91101", "notes", "Front desk"));
        assertThat(a.status()).isEqualTo(201);
        assertThat(a.body().get("city").asText()).isEqualTo("Pasadena");
        assertThat(a.body().get("lat").asDouble()).isEqualTo(34.1468);
        long id = a.body().get("id").asLong();

        assertThat(http.post("/api/me/addresses", c, Map.of("label", "NYC", "line1", "1 Broadway", "zip", "10004")).code())
                .isEqualTo("outside_service_area");
        assertThat(http.post("/api/me/addresses", c, Map.of("label", "Bad", "line1", "x", "zip", "91101",
                "pinLat", 34.5, "pinLng", -118.1)).code()).isEqualTo("pin_outside_zip");
        var moved = http.put("/api/me/addresses/" + id, c, Map.of("label", "Work", "line1", "55 S Lake Ave", "zip", "91101",
                "pinLat", 34.1455, "pinLng", -118.1325));
        assertThat(moved.body().get("lng").asDouble()).isEqualTo(-118.1325);

        // other people's addresses are invisible
        String other = newCustomer();
        assertThat(http.put("/api/me/addresses/" + id, other, Map.of("label", "x", "line1", "x", "zip", "91101")).status()).isEqualTo(404);
        assertThat(http.delete("/api/me/addresses/" + id, other).status()).isEqualTo(404);
        assertThat(http.delete("/api/me/addresses/" + id, c).status()).isEqualTo(204);
        assertThat(http.get("/api/me/addresses", c).body()).isEmpty();
        assertThat(http.get("/api/geo/zips/91776", null).body().get("city").asText()).isEqualTo("San Gabriel");
        assertThat(http.get("/api/geo/zips/90210", null).status()).isEqualTo(404);
    }

    @Test
    void storefrontQuotesAndSortsByDistance() {
        // a point in Alhambra: Xi'an Spice Road (Rowland Heights, ~22 km) cannot deliver there
        var list = http.get("/api/restaurants?lat=34.0911&lng=-118.1293", null).body();
        var first = list.get(0);
        assertThat(first.get("delivery").get("deliverable").asBoolean()).isTrue();
        var xian = java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(r -> r.get("slug").asText().equals("xian-spice-road")).findFirst().orElseThrow();
        assertThat(xian.get("delivery").get("deliverable").asBoolean()).isFalse();
        assertThat(xian.get("delivery").get("distanceM").asInt()).isGreaterThan(20_000);
        // deliverable first, then nearest first
        int last = -1;
        boolean seenUndeliverable = false;
        for (var r : list) {
            boolean ok = r.get("delivery").get("deliverable").asBoolean();
            if (!ok) {
                seenUndeliverable = true;
                continue;
            }
            assertThat(seenUndeliverable).as("deliverable after undeliverable").isFalse();
            assertThat(r.get("delivery").get("distanceM").asInt()).isGreaterThanOrEqualTo(last);
            last = r.get("delivery").get("distanceM").asInt();
        }
        // no point: no quotes
        assertThat(http.get("/api/restaurants", null).body().get(0).has("delivery")).isFalse();
        assertThat(http.get("/api/restaurants?lat=999&lng=0", null).code()).isEqualTo("bad_location");
    }

    @Test
    void ordersArePricedByDistanceAndRefusedOutOfRange() {
        var k = newKitchen(5);
        String c = newCustomer();
        // a pin 2.5 km from the kitchen (pins may be up to 3 km from the ZIP centroid): 3.00 base + 0.5 km x 0.60 = 3.30
        GeoPoint home = new GeoPoint(34.0911, -118.1293).offset(2_500, 0);
        long near = http.post("/api/me/addresses", c, Map.of("label", "Near", "line1", "9 North St", "zip", "91801",
                "pinLat", home.lat(), "pinLng", home.lng())).body().get("id").asLong();
        var placed = http.post("/api/orders", c, Map.of("restaurantId", k.id(),
                "items", java.util.List.of(Map.of("dishId", k.plainDish(), "quantity", 1)), "addressId", near,
                "deliveryNotes", "ring twice"), Map.of("Idempotency-Key", unique("k")));
        assertThat(placed.status()).isEqualTo(201);
        assertThat(placed.body().get("deliveryFeeCents").asInt()).isEqualTo(330);
        assertThat(placed.body().get("totalCents").asInt()).isEqualTo(1330);
        assertThat(placed.body().get("deliveryAddress").asText()).isEqualTo("9 North St, Alhambra 91801");
        UUID id = UUID.fromString(placed.body().get("id").asText());
        assertThat(db.sql("select distance_m from orders where id = ?").param(id).query(Integer.class).single()).isBetween(2490, 2510);
        assertThat(db.sql("select amount_cents from payments where order_id = ?").param(id).query(Integer.class).single()).isEqualTo(1330);

        // an address in Rowland Heights is out of the 8 km radius
        long far = http.post("/api/me/addresses", c, Map.of("label", "Far", "line1", "1 Colima Rd", "zip", "91748"))
                .body().get("id").asLong();
        var refused = http.post("/api/orders", c, Map.of("restaurantId", k.id(),
                "items", java.util.List.of(Map.of("dishId", k.plainDish(), "quantity", 1)), "addressId", far),
                Map.of("Idempotency-Key", unique("k")));
        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("out_of_range");
        // someone else's address id cannot be used
        String other = newCustomer();
        assertThat(http.post("/api/orders", other, Map.of("restaurantId", k.id(),
                "items", java.util.List.of(Map.of("dishId", k.plainDish(), "quantity", 1)), "addressId", near),
                Map.of("Idempotency-Key", unique("k"))).code()).isEqualTo("unknown_address");
    }

}
