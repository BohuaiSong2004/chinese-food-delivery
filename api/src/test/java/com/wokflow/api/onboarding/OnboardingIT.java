package com.wokflow.api.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class OnboardingIT extends IntegrationTest {

    static Map<String, Object> profile(String name) {
        return Map.ofEntries(Map.entry("name", name), Map.entry("nameZh", "新店"), Map.entry("description", "Hand-pulled noodles"),
                Map.entry("cuisine", "northwestern"), Map.entry("tags", List.of("noodles")), Map.entry("neighborhood", "Alhambra"),
                Map.entry("address", "1 Valley Blvd, Alhambra, CA"), Map.entry("deliveryFeeCents", 199),
                Map.entry("minOrderCents", 0), Map.entry("prepMinutes", 15), Map.entry("opensAt", "00:00"),
                Map.entry("closesAt", "00:00"), Map.entry("open24h", true));
    }

    @Test
    void applicationIsInvisibleUntilApprovedThenFullyLive() {
        String email = unique("owner") + "@example.com";
        String owner = newAccount("MERCHANT", email);
        String name = "Noodle Lab " + unique("");
        var applied = http.post("/api/merchant/applications", owner, profile(name));
        assertThat(applied.status()).isEqualTo(201);
        assertThat(applied.body().get("status").asText()).isEqualTo("PENDING");
        long rid = applied.body().get("id").asLong();
        String slug = applied.body().get("slug").asText();
        assertThat(slug).startsWith("noodle-lab-");

        // a fresh token carries the new restaurant: the owner builds the menu while under review
        owner = signIn(email, "correct-horse-1");
        long cat = http.post("/api/merchant/restaurants/" + rid + "/categories", owner, Map.of("name", "Noodles", "nameZh", "面"))
                .body().get("id").asLong();
        var dish = http.post("/api/merchant/restaurants/" + rid + "/dishes", owner, Map.of("categoryId", cat,
                "name", "Zanzibar Knife-Cut Noodles", "nameZh", "刀削面试吃", "priceCents", 1450, "tags", List.of("noodles"),
                "spiceLevel", 1, "available", true));
        assertThat(dish.status()).isEqualTo(201);

        // invisible to customers: not listed, no public menu, not searchable, not orderable
        assertThat(http.get("/api/restaurants", null).body().toString()).doesNotContain(slug);
        assertThat(http.get("/api/restaurants/" + slug, null).status()).isEqualTo(404);
        String customer = newCustomer();
        assertThat(placeOrder(customer, rid, Map.of(dish.body().get("id").asLong(), 1), unique("k")).status()).isEqualTo(404);

        String admin = signIn("admin@example.com");
        var queue = http.get("/api/admin/applications", admin).body();
        assertThat(queue.get("restaurants").toString()).contains(slug).contains(email);
        assertThat(http.post("/api/admin/restaurants/" + rid + "/reject", admin, Map.of()).code()).isEqualTo("note_required");
        assertThat(http.post("/api/admin/restaurants/" + rid + "/approve", owner, null).status()).isEqualTo(403);
        assertThat(http.post("/api/admin/restaurants/" + rid + "/approve", admin, null).body().get("status").asText()).isEqualTo("LIVE");
        assertThat(http.post("/api/admin/restaurants/" + rid + "/approve", admin, null).code()).isEqualTo("invalid_transition");

        assertThat(http.get("/api/restaurants", null).body().toString()).contains(slug);
        assertThat(http.get("/api/restaurants/" + slug, null).body().toString()).contains("Zanzibar Knife-Cut Noodles");
        waitUntil(() -> http.get("/api/search?q=zanzibar", null).body().toString().contains(slug));
        assertThat(placeOrder(customer, rid, Map.of(dish.body().get("id").asLong(), 1), unique("k")).status()).isEqualTo(201);
        // approval opened the restaurant's ledger account, so the capture can post
        assertThat(db.sql("select count(*) from ledger_accounts where restaurant_id = ?").param(rid).query(Long.class).single()).isOne();

        // suspension hides it again but keeps everything
        assertThat(http.post("/api/admin/restaurants/" + rid + "/suspend", admin, Map.of("note", "health inspection")).status()).isEqualTo(200);
        assertThat(http.get("/api/restaurants/" + slug, null).status()).isEqualTo(404);
        waitUntil(() -> !http.get("/api/search?q=zanzibar", null).body().toString().contains(slug));
        var mine = http.get("/api/merchant/restaurants", owner).body().get(0);
        assertThat(mine.get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(mine.get("statusNote").asText()).isEqualTo("health inspection");
        http.post("/api/admin/restaurants/" + rid + "/reinstate", admin, null);
        assertThat(http.get("/api/restaurants/" + slug, null).status()).isEqualTo(200);

        var audit = http.get("/api/admin/audit?limit=200", admin).body().toString();
        assertThat(audit).contains("restaurant.approve").contains("restaurant.suspend").contains("restaurant:" + rid);
    }

    @Test
    void rejectedApplicationCanBeFixedAndResubmitted() {
        String email = unique("owner") + "@example.com";
        String owner = newAccount("MERCHANT", email);
        long rid = http.post("/api/merchant/applications", owner, profile("Second Try " + unique(""))).body().get("id").asLong();
        owner = signIn(email, "correct-horse-1");
        String admin = signIn("admin@example.com");
        http.post("/api/admin/restaurants/" + rid + "/reject", admin, Map.of("note", "Add an English description"));

        var settings = http.get("/api/merchant/restaurants/" + rid + "/settings", owner).body();
        assertThat(settings.get("status").asText()).isEqualTo("REJECTED");
        assertThat(settings.get("statusNote").asText()).isEqualTo("Add an English description");
        long version = settings.get("version").asLong();
        var p = new java.util.HashMap<>(profile("Second Try Fixed"));
        p.put("description", "Now with a proper description");
        assertThat(http.put("/api/merchant/restaurants/" + rid + "/settings", owner, Map.of("profile", p, "version", version))
                .body().get("profile").get("description").asText()).isEqualTo("Now with a proper description");
        // the same stale version again is refused
        assertThat(http.put("/api/merchant/restaurants/" + rid + "/settings", owner, Map.of("profile", p, "version", version)).code())
                .isEqualTo("stale_version");
        assertThat(http.post("/api/merchant/restaurants/" + rid + "/resubmit", owner, null).status()).isEqualTo(200);
        assertThat(http.post("/api/merchant/restaurants/" + rid + "/resubmit", owner, null).code()).isEqualTo("not_rejected");
        assertThat(http.get("/api/admin/applications", admin).body().get("restaurants").toString()).contains("\"id\":" + rid);
    }

    @Test
    void validationAndLimits() throws Exception {
        String customer = newCustomer();
        assertThat(http.post("/api/merchant/applications", customer, profile("Nope")).status()).isEqualTo(403);
        String owner = newAccount("MERCHANT", unique("owner") + "@example.com");
        var bad = new java.util.HashMap<>(profile("Bad Cuisine"));
        bad.put("cuisine", "martian");
        assertThat(http.post("/api/merchant/applications", owner, bad).code()).isEqualTo("unknown_cuisine");
        bad = new java.util.HashMap<>(profile("Bad Hours"));
        bad.put("open24h", false);
        assertThat(http.post("/api/merchant/applications", owner, bad).code()).isEqualTo("bad_hours");
        bad = new java.util.HashMap<>(profile("Bad Tag"));
        bad.put("tags", List.of("cheap"));
        assertThat(http.post("/api/merchant/applications", owner, bad).code()).isEqualTo("unknown_tag");
        assertThat(http.post("/api/auth/signup", null, Map.of("email", unique("x") + "@example.com", "password", "correct-horse-1",
                "name", "Sneaky", "accountType", "ADMIN")).status()).isEqualTo(400);

        // at most three applications under review per owner, even when sent all at once
        final String o = owner;
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int n = i;
            calls.add(() -> http.post("/api/merchant/applications", o, profile("Same Name")).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(3);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
        // identical names still get distinct slugs
        assertThat(db.sql("select count(distinct slug) from restaurants where name = 'Same Name'").query(Long.class).single())
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void ridersWaitForApproval() {
        String email = unique("rider") + "@example.com";
        String rider = newAccount("RIDER", email);
        var jobs = http.get("/api/rider/jobs", rider);
        assertThat(jobs.status()).isEqualTo(403);
        assertThat(jobs.code()).isEqualTo("pending_approval");
        long uid = db.sql("select id from users where email = ?").param(email).query(Long.class).single();

        String admin = signIn("admin@example.com");
        assertThat(http.get("/api/admin/applications", admin).body().get("riders").toString()).contains(email);
        assertThat(http.post("/api/admin/riders/" + uid + "/approve", admin, null).status()).isEqualTo(200);
        assertThat(http.post("/api/admin/riders/" + uid + "/approve", admin, null).code()).isEqualTo("not_pending");
        // the old token still says pending until it is refreshed; a new session is approved
        rider = signIn(email, "correct-horse-1");
        assertThat(http.get("/api/rider/jobs", rider).status()).isEqualTo(200);

        String other = unique("rider") + "@example.com";
        newAccount("RIDER", other);
        long otherId = db.sql("select id from users where email = ?").param(other).query(Long.class).single();
        assertThat(http.post("/api/admin/riders/" + otherId + "/reject", admin, Map.of()).code()).isEqualTo("note_required");
        http.post("/api/admin/riders/" + otherId + "/reject", admin, Map.of("note", "Licence expired"));
        assertThat(http.get("/api/admin/applications", admin).body().get("riders").toString()).doesNotContain(other);
        assertThat(http.get("/api/rider/jobs", signIn(other, "correct-horse-1")).code()).isEqualTo("pending_approval");
    }
}
