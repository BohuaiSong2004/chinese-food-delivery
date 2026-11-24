package com.wokflow.api.images;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ImageIT extends IntegrationTest {
    @Autowired
    DemoImages demoImages;

    @Test
    void merchantUploadsADishPhotoThatShowsEverywhere() throws Exception {
        var k = newKitchen(1);
        String m = signIn(k.merchantEmail());
        String word = "marigold" + Long.toString(System.nanoTime() % 1_000_000, 36).replaceAll("[0-9]", "x");
        db.sql("update dishes set name = ? where id = ?").params("Fried Rice " + word, k.plainDish()).update();
        var res = http.upload("/api/merchant/dishes/" + k.plainDish() + "/image", m, "rice.png", "image/png",
                ImageServiceTest.png(1600, 1200));
        assertThat(res.status()).isEqualTo(200);
        String url = res.body().get("imageKey").asText();
        assertThat(url).matches("d/[0-9a-f]{20}");

        String base = "/api/images/" + url;
        var img = http.bytes(base + "-640.jpg");
        assertThat(img.statusCode()).isEqualTo(200);
        assertThat(img.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(img.headers().firstValue("Cache-Control").orElse("")).contains("immutable").contains("max-age=31536000");
        assertThat(http.bytes("/api/images/d/00000000000000000000-640.jpg").statusCode()).isEqualTo(404);
        assertThat(http.bytes("/api/images/d/../../etc/passwd").statusCode()).isIn(400, 401, 404); // never reaches the store

        // public menu (cached: menu_version moved), restaurant cover fallback and search all carry it
        var menu = http.get("/api/restaurants/" + k.slug(), null).body();
        assertThat(menu.toString()).contains("\"image\":\"" + base + "\"");
        assertThat(menu.get("restaurant").get("image").asText()).isEqualTo(base);
        // every test kitchen sells a "Fried Rice", so search for this kitchen's own (renamed) one
        waitUntil(() -> http.get("/api/search?q=" + word, null).body().toString().contains(base));

        // wrong type, other people's dishes, customers
        assertThat(http.upload("/api/merchant/dishes/" + k.plainDish() + "/image", m, "x.gif", "image/gif",
                "GIF89a............".getBytes()).status()).isEqualTo(415);
        String stranger = signIn("partners@example.com");
        assertThat(http.upload("/api/merchant/dishes/" + k.plainDish() + "/image", stranger, "rice.png", "image/png",
                ImageServiceTest.png(800, 600)).status()).isEqualTo(403);
        assertThat(http.upload("/api/merchant/dishes/" + k.plainDish() + "/image", newCustomer(), "rice.png", "image/png",
                ImageServiceTest.png(800, 600)).status()).isEqualTo(403);

        // a cover photo overrides the dish fallback; removing the dish photo clears it
        var cover = http.upload("/api/merchant/restaurants/" + k.id() + "/image", m, "front.png", "image/png",
                ImageServiceTest.png(2000, 1000));
        assertThat(cover.body().get("image").asText()).isNotEqualTo(base);
        assertThat(http.get("/api/restaurants/" + k.slug(), null).body().get("restaurant").get("image").asText())
                .isEqualTo(cover.body().get("image").asText());
        assertThat(http.delete("/api/merchant/dishes/" + k.plainDish() + "/image", m).body().has("imageKey")).isFalse();
    }

    @Test
    void demoDishesGetIllustrationsOnceAndKeepUploads() {
        long dish = dishId("lucky-star-dumplings", "Egg & Chive Dumplings");
        db.sql("update dishes set image_key = 'd/0123456789abcdef0123' where id = ?").param(dish).update();
        demoImages.generate();
        assertThat(db.sql("select count(*) from dishes d join restaurants r on r.id = d.restaurant_id "
                + "where r.slug = 'lucky-star-dumplings' and d.image_key is null and d.archived_at is null").query(Long.class).single()).isZero();
        // a merchant's own photo is never replaced
        assertThat(db.sql("select image_key from dishes where id = ?").param(dish).query(String.class).single())
                .isEqualTo("d/0123456789abcdef0123");
        assertThat(demoImages.generate()).isZero();
        db.sql("update dishes set image_key = null where id = ?").param(dish).update();
    }
}
