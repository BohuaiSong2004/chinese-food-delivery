package com.wokflow.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class SearchIT extends IntegrationTest {
    @Autowired
    SearchIndexer indexer;
    @Autowired
    SearchService search;
    @Autowired
    ElasticsearchClient es;

    @BeforeEach
    void indexed() throws Exception {
        for (int i = 0; i < 100 && !indexer.isReady(); i++) {
            indexer.ensureReady();
            Thread.sleep(200);
        }
        assertThat(indexer.isReady()).isTrue();
    }

    List<String> names(String query) {
        return names(http.get("/api/search?size=5&q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8), null).body());
    }

    static List<String> names(JsonNode res) {
        assertThat(res.get("degraded").asBoolean()).isFalse();
        List<String> out = new ArrayList<>();
        res.get("hits").forEach(h -> out.add(h.get("nameZh").asText()));
        return out;
    }

    @Test
    void englishWithTyposChineseAndThreeKindsOfPinyinAllFindTheDish() {
        assertThat(names("mapo tofu").getFirst()).isEqualTo("麻婆豆腐");
        assertThat(names("mapo tofo").getFirst()).isEqualTo("麻婆豆腐");       // typo
        assertThat(names("麻婆豆腐").getFirst()).isEqualTo("麻婆豆腐");         // characters
        assertThat(names("mapo doufu").getFirst()).isEqualTo("麻婆豆腐");       // spaced pinyin
        assertThat(names("mapodoufu").getFirst()).isEqualTo("麻婆豆腐");        // joined pinyin
        assertThat(names("mpdf").getFirst()).isEqualTo("麻婆豆腐");             // initials
        assertThat(names("xiaolong")).contains("鲜肉小笼包", "蟹粉小笼包");      // pinyin prefix
        assertThat(names("dumplng")).isNotEmpty();                             // typo, plural stem
        assertThat(names("牛肉面")).contains("红烧牛肉面", "兰州牛肉拉面");
        assertThat(names("面")).hasSize(5);                                     // single character
        assertThat(names("xlb")).contains("鲜肉小笼包");                         // initials of part of the name
        assertThat(names("biangbiang").getFirst()).isEqualTo("油泼扯面");        // run-together English
    }

    @Test
    void tagAndSpiceFiltersApplyAndFacetsAreReturned() {
        var res = http.get("/api/search?q=noodles&tag=vegan&size=50", null).body();
        assertThat(res.get("total").asInt()).isPositive();
        res.get("hits").forEach(h -> assertThat(h.get("tags").toString()).contains("vegan"));
        var mild = http.get("/api/search?tag=halal&maxSpice=0&size=50", null).body();
        mild.get("hits").forEach(h -> {
            assertThat(h.get("spiceLevel").asInt()).isZero();
            assertThat(h.get("tags").toString()).contains("halal");
        });
        var all = http.get("/api/search?size=1", null).body();
        assertThat(all.get("facets").get("tags").toString()).contains("vegetarian", "noodles");
        assertThat(all.get("facets").get("cuisine").toString()).contains("sichuan", "cantonese");
        assertThat(http.get("/api/search?tag=" + "%3Cscript%3E", null).status()).isEqualTo(400);
        assertThat(http.get("/api/search?maxSpice=9", null).status()).isEqualTo(400);
    }

    @Test
    void merchantEditsReachTheIndexThroughTheOutbox() throws Exception {
        long dish = dishId("hunan-smokehouse", "Tiger Skin Peppers");
        String merchant = signIn("partners@example.com");
        long version = db.sql("select version from dishes where id = ?").param(dish).query(Long.class).single();
        var r = http.patch("/api/merchant/dishes/" + dish, merchant, Map.of("priceCents", 1399, "available", false, "version", version));
        assertThat(r.status()).isEqualTo(200);
        assertThat(eventually(() -> !names("虎皮青椒").contains("虎皮青椒"))).isTrue();
        // a stale edit (old version) is refused
        assertThat(http.patch("/api/merchant/dishes/" + dish, merchant, Map.of("priceCents", 1, "available", true, "version", version)).code())
                .isEqualTo("stale_version");
        http.patch("/api/merchant/dishes/" + dish, merchant, Map.of("priceCents", 1295, "available", true, "version", version + 1));
        assertThat(eventually(() -> names("虎皮青椒").contains("虎皮青椒"))).isTrue();
    }

    @Test
    void aLostIndexIsRebuiltFromPostgres() throws Exception {
        var aliases = es.indices().getAlias(g -> g.name(indexer.alias())).aliases().keySet();
        for (String physical : aliases) {
            es.indices().delete(d -> d.index(physical));
        }
        assertThat(indexer.aliasExists()).isFalse();
        // while it is gone, search degrades to Postgres rather than failing
        var degraded = search.search(new SearchService.Criteria("Mapo", List.of(), List.of(), null, 0, 5));
        assertThat(degraded.degraded()).isTrue();
        assertThat(degraded.hits().getFirst().nameZh()).isEqualTo("麻婆豆腐");

        indexer.ensureReady();
        assertThat(indexer.aliasExists()).isTrue();
        assertThat(names("mpdf").getFirst()).isEqualTo("麻婆豆腐");
    }

    @Test
    void aChangedMappingTriggersARebuild() throws Exception {
        assertThat(indexer.liveMappingHash()).isEqualTo(SearchIndexer.mappingHash());
        String before = es.indices().getAlias(g -> g.name(indexer.alias())).aliases().keySet().iterator().next();
        es.indices().putMapping(p -> p.index(before).meta("mapping_hash", co.elastic.clients.json.JsonData.of("old")));
        indexer.ensureReady();
        String after = es.indices().getAlias(g -> g.name(indexer.alias())).aliases().keySet().iterator().next();
        assertThat(after).isNotEqualTo(before);
        assertThat(indexer.liveMappingHash()).isEqualTo(SearchIndexer.mappingHash());
    }

    @Test
    void postgresFallbackFiltersToo() {
        var r = search.postgres(new SearchService.Criteria(null, List.of("vegan"), List.of("sichuan"), 2, 0, 50));
        assertThat(r.hits()).isNotEmpty().allSatisfy(h -> {
            assertThat(h.tags()).contains("vegan");
            assertThat(h.cuisine()).isEqualTo("sichuan");
            assertThat(h.spiceLevel()).isLessThanOrEqualTo(2);
        });
        // LIKE wildcards in user input are escaped
        assertThat(search.postgres(new SearchService.Criteria("%", List.of(), List.of(), null, 0, 5)).hits()).isEmpty();
    }

    @Test
    void pinyinTranscription() {
        assertThat(Pinyin.of("麻婆豆腐")).isEqualTo(new Pinyin.Reading("ma po dou fu", "mapodoufu", "mpdf"));
        assertThat(Pinyin.of("绿豆汤").initials()).isEqualTo("ldt");
        assertThat(Pinyin.of("XO酱").syllables()).isEqualTo("x o jiang");
    }

    static boolean eventually(java.util.function.BooleanSupplier check) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }
}
