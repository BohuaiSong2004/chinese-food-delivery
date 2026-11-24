package com.wokflow.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewIT extends IntegrationTest {

    @Test
    void customersReviewDeliveredOrdersOnceAndRatingsFollow() {
        var k = newKitchen(5);
        String alice = newCustomer();
        UUID first = placeOrder(alice, k.id(), Map.of(k.plainDish(), 1));
        // not before delivery
        assertThat(http.post("/api/orders/" + first + "/review", alice, Map.of("rating", 5)).code()).isEqualTo("not_delivered");
        deliver(first, k);
        assertThat(http.post("/api/orders/" + first + "/review", alice, Map.of("rating", 6)).status()).isEqualTo(400);
        var r = http.post("/api/orders/" + first + "/review", alice, Map.of("rating", 5, "comment", "  Crispy and hot  "));
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("comment").asText()).isEqualTo("Crispy and hot");
        assertThat(r.body().get("author").asText()).isEqualTo("Test C."); // never the full name or email
        assertThat(http.post("/api/orders/" + first + "/review", alice, Map.of("rating", 1)).code()).isEqualTo("already_reviewed");
        // only the customer who received it
        String bob = newCustomer();
        assertThat(http.post("/api/orders/" + first + "/review", bob, Map.of("rating", 1)).status()).isEqualTo(404);
        assertThat(http.get("/api/orders/" + first, alice).body().get("review").get("rating").asInt()).isEqualTo(5);

        UUID second = placeOrder(bob, k.id(), Map.of(k.plainDish(), 1));
        deliver(second, k);
        http.post("/api/orders/" + second + "/review", bob, Map.of("rating", 2, "comment", "Cold when it arrived"));

        var page = http.get("/api/restaurants/" + k.slug() + "/reviews", null).body();
        assertThat(page.get("rating").get("average").asDouble()).isEqualTo(3.5);
        assertThat(page.get("rating").get("count").asInt()).isEqualTo(2);
        assertThat(page.get("page").get("items").get(0).get("rating").asInt()).isEqualTo(2); // newest first
        var card = java.util.stream.StreamSupport.stream(http.get("/api/restaurants", null).body().spliterator(), false)
                .filter(x -> x.get("slug").asText().equals(k.slug())).findFirst().orElseThrow();
        assertThat(card.get("rating").asDouble()).isEqualTo(3.5);
        assertThat(card.get("ratingCount").asInt()).isEqualTo(2);

        // the restaurant replies; a stranger cannot
        String merchant = signIn(k.merchantEmail());
        assertThat(http.post("/api/merchant/reviews/" + second + "/reply", merchant, Map.of("reply", "Sorry! New insulated bags this week."))
                .body().get("reply").asText()).startsWith("Sorry!");
        assertThat(http.post("/api/merchant/reviews/" + second + "/reply", signIn("partners@example.com"), Map.of("reply", "x")).status())
                .isEqualTo(403);

        // an admin hides an abusive review: out of the list and out of the rating, and back again
        String admin = signIn("admin@example.com");
        assertThat(http.post("/api/admin/reviews/" + second + "/hide", admin, Map.of()).code()).isEqualTo("note_required");
        http.post("/api/admin/reviews/" + second + "/hide", admin, Map.of("note", "personal attack"));
        page = http.get("/api/restaurants/" + k.slug() + "/reviews", null).body();
        assertThat(page.get("rating").get("average").asDouble()).isEqualTo(5.0);
        assertThat(page.get("page").get("items")).hasSize(1);
        assertThat(http.get("/api/merchant/restaurants/" + k.id() + "/reviews", merchant).body().get("page").get("items")).hasSize(2);
        assertThat(http.post("/api/admin/reviews/" + second + "/hide", admin, Map.of("note", "again")).code()).isEqualTo("no_change");
        http.post("/api/admin/reviews/" + second + "/unhide", admin, null);
        assertThat(http.get("/api/restaurants/" + k.slug() + "/reviews", null).body().get("rating").get("count").asInt()).isEqualTo(2);

        // the running sum always equals the reviews it summarizes
        assertThat(db.sql("""
                select count(*) from restaurants r where r.rating_sum <> coalesce((select sum(rating) from reviews v
                    where v.restaurant_id = r.id and v.hidden_at is null), 0)
                  or r.rating_count <> (select count(*) from reviews v where v.restaurant_id = r.id and v.hidden_at is null)""")
                .query(Long.class).single()).isZero();
    }

    @Test
    void theReviewWindowClosesAndPagesAreStable() {
        var k = newKitchen(5);
        String c = newCustomer();
        UUID old = placeOrder(c, k.id(), Map.of(k.plainDish(), 1));
        deliver(old, k);
        clock.advance(Duration.ofDays(15));
        assertThat(http.post("/api/orders/" + old + "/review", c, Map.of("rating", 4)).code()).isEqualTo("review_window_closed");
        clock.reset();

        // 12 reviews, read 5 at a time: every review exactly once, newest first
        for (int i = 0; i < 12; i++) {
            String who = newCustomer();
            UUID o = placeOrder(who, k.id(), Map.of(k.plainDish(), 1));
            deliver(o, k);
            http.post("/api/orders/" + o + "/review", who, Map.of("rating", 1 + i % 5, "comment", "review " + i));
        }
        java.util.List<String> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            var page = http.get("/api/restaurants/" + k.slug() + "/reviews?limit=5" + (cursor == null ? "" : "&cursor=" + cursor), null)
                    .body().get("page");
            page.get("items").forEach(x -> seen.add(x.get("comment").asText()));
            cursor = page.has("next") ? page.get("next").asText() : null;
            pages++;
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).hasSize(12).doesNotHaveDuplicates().startsWith("review 11");
        assertThat(http.get("/api/restaurants/" + k.slug() + "/reviews?cursor=not-a-cursor", null).code()).isEqualTo("bad_cursor");
    }
}
