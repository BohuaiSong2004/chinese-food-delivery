package com.wokflow.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MenuEditorIT extends IntegrationTest {

    static Map<String, Object> dish(long category, String name, int price, Integer stock) {
        var m = new HashMap<String, Object>(Map.of("categoryId", category, "name", name, "nameZh", "新菜", "priceCents", price,
                "tags", List.of("signature"), "spiceLevel", 2, "available", true, "description", "Fresh"));
        if (stock != null) {
            m.put("dailyStock", stock);
        }
        return m;
    }

    @Test
    void createEditArchiveAndRecreateADish() {
        var k = newKitchen(10);
        String m = signIn(k.merchantEmail());
        long cat = http.post("/api/merchant/restaurants/" + k.id() + "/categories", m, Map.of("name", "Chef's Specials", "nameZh", "主厨推荐"))
                .body().get("id").asLong();
        var created = http.post("/api/merchant/restaurants/" + k.id() + "/dishes", m, dish(cat, "Typhoon Shelter Crab", 3800, 5)).body();
        long id = created.get("id").asLong();
        assertThat(created.get("stockRemaining").asInt()).isEqualTo(5);

        // visible on the cached public menu straight away (menu_version is part of the cache key)
        assertThat(http.get("/api/restaurants/" + k.slug(), null).body().toString()).contains("Typhoon Shelter Crab").contains("主厨推荐");
        waitUntil(() -> http.get("/api/search?q=typhoon", null).body().toString().contains("Typhoon Shelter Crab"));

        // sell two, then raise the limit: remaining moves by the difference
        String customer = newCustomer();
        placeOrder(customer, k.id(), Map.of(id, 2));
        var edit = dish(cat, "Typhoon Shelter Crab", 3900, 8);
        var updated = http.put("/api/merchant/dishes/" + id, m, Map.of("dish", edit, "version", created.get("version").asLong()));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().get("stockRemaining").asInt()).isEqualTo(6);
        assertThat(updated.body().get("priceCents").asInt()).isEqualTo(3900);
        assertThat(http.put("/api/merchant/dishes/" + id, m, Map.of("dish", edit, "version", created.get("version").asLong())).code())
                .isEqualTo("stale_version");

        // duplicate names, unknown tags and foreign sections are refused
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/dishes", m, dish(cat, "Typhoon Shelter Crab", 100, null)).code())
                .isEqualTo("name_taken");
        var badTag = dish(cat, "Other", 100, null);
        badTag.put("tags", List.of("cheap"));
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/dishes", m, badTag).code()).isEqualTo("unknown_tag");
        var other = newKitchen(1);
        long foreignCat = db.sql("select id from menu_categories where restaurant_id = ?").param(other.id()).query(Long.class).single();
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/dishes", m, dish(foreignCat, "Other", 100, null)).code())
                .isEqualTo("unknown_section");
        assertThat(http.post("/api/merchant/restaurants/" + other.id() + "/dishes", m, dish(foreignCat, "Other", 100, null)).status())
                .isEqualTo(403);

        // a section with dishes cannot go; an archived dish leaves the menu, search and ordering
        assertThat(http.delete("/api/merchant/categories/" + cat, m).code()).isEqualTo("section_not_empty");
        assertThat(http.delete("/api/merchant/dishes/" + id, m).status()).isEqualTo(204);
        assertThat(http.get("/api/restaurants/" + k.slug(), null).body().toString()).doesNotContain("Typhoon Shelter Crab");
        assertThat(placeOrder(customer, k.id(), Map.of(id, 1), unique("k")).code()).isEqualTo("dish_unavailable");
        waitUntil(() -> !http.get("/api/search?q=typhoon", null).body().toString().contains("Typhoon Shelter Crab"));
        // the old order still shows the dish
        assertThat(http.get("/api/orders", customer).body().toString()).contains("Typhoon Shelter Crab");

        // the name is free again once archived, and the empty section can be removed
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/dishes", m, dish(cat, "Typhoon Shelter Crab", 4000, null)).status())
                .isEqualTo(201);
        long newest = db.sql("select id from dishes where restaurant_id = ? and name = 'Typhoon Shelter Crab' and archived_at is null")
                .param(k.id()).query(Long.class).single();
        http.delete("/api/merchant/dishes/" + newest, m);
        assertThat(http.delete("/api/merchant/categories/" + cat, m).status()).isEqualTo(204);
    }

    @Test
    void sectionsRenameAndReorder() {
        var k = newKitchen(1);
        String m = signIn(k.merchantEmail());
        long mains = db.sql("select id from menu_categories where restaurant_id = ?").param(k.id()).query(Long.class).single();
        long drinks = http.post("/api/merchant/restaurants/" + k.id() + "/categories", m, Map.of("name", "Drinks", "nameZh", "饮品"))
                .body().get("id").asLong();
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/categories", m, Map.of("name", "Drinks", "nameZh", "饮品")).code())
                .isEqualTo("name_taken");
        // reorder must list every section exactly once
        assertThat(http.post("/api/merchant/restaurants/" + k.id() + "/categories/order", m, Map.of("ids", List.of(drinks))).code())
                .isEqualTo("stale_order");
        var order = http.post("/api/merchant/restaurants/" + k.id() + "/categories/order", m, Map.of("ids", List.of(drinks, mains))).body();
        assertThat(order.get(0).get("id").asLong()).isEqualTo(drinks);
        long version = order.get(0).get("version").asLong();
        var renamed = http.put("/api/merchant/categories/" + drinks, m,
                Map.of("category", Map.of("name", "Tea & Drinks", "nameZh", "茶饮"), "version", version));
        assertThat(renamed.body().get("name").asText()).isEqualTo("Tea & Drinks");
        assertThat(http.put("/api/merchant/categories/" + drinks, m,
                Map.of("category", Map.of("name", "X", "nameZh", "X"), "version", version)).code()).isEqualTo("stale_version");
        // an empty section is hidden from the public menu
        assertThat(http.get("/api/restaurants/" + k.slug(), null).body().toString()).doesNotContain("Tea & Drinks");
    }
}
