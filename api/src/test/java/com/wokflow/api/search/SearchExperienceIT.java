package com.wokflow.api.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/** Synonyms, the relaxed second pass, highlighting, sorting, suggestions and the miss log. */
class SearchExperienceIT extends IntegrationTest {
    @Autowired
    SearchIndexer indexer;
    @Autowired
    SearchMisses misses;

    @BeforeEach
    void indexed() throws Exception {
        for (int i = 0; i < 100 && !indexer.isReady(); i++) {
            indexer.ensureReady();
            Thread.sleep(200);
        }
        assertThat(indexer.isReady()).isTrue();
    }

    JsonNode search(String query, String extra) {
        var r = http.get("/api/search?size=10&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + extra, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("degraded").asBoolean()).isFalse();
        return r.body();
    }

    static List<String> zh(JsonNode res) {
        List<String> out = new ArrayList<>();
        res.get("hits").forEach(h -> out.add(h.get("nameZh").asText()));
        return out;
    }

    @Test
    void otherNamesForADishFindIt() {
        assertThat(zh(search("boba", "")).getFirst()).isEqualTo("珍珠奶茶");
        assertThat(zh(search("馄饨", ""))).contains("云吞面", "红油抄手");
        assertThat(zh(search("kung pow chicken", "")).getFirst()).isEqualTo("宫保鸡丁");
        assertThat(zh(search("lamb kebab", "")).getFirst()).isIn("羊肉串", "孜然羊肉串");
        // what was typed ranks above a synonym: "tofu" finds the tofu dish, not everything with "bean"
        assertThat(zh(search("tofu", "")).getFirst()).isEqualTo("麻婆豆腐");
    }

    @Test
    void whenEveryWordMatchesNothingSomeWordsMayAndTheResultSaysSo() {
        var strict = search("mapo tofu", "");
        assertThat(strict.has("relaxed") && strict.get("relaxed").asBoolean()).isFalse();

        var relaxed = search("spicy fish", "");
        assertThat(relaxed.get("relaxed").asBoolean()).isTrue();
        assertThat(zh(relaxed)).contains("水煮鱼");

        var nothing = search("zzqx wvvy", "");
        assertThat(nothing.get("total").asInt()).isZero();
        assertThat(nothing.get("hits")).isEmpty();
    }

    @Test
    void whenFiltersLeaveNothingTheCountWithoutThemIsGiven() {
        var res = search("noodles", "&tag=dessert");
        assertThat(res.get("total").asInt()).isZero();
        assertThat(res.get("withoutFilters").asInt()).isGreaterThan(5);
        // no filters, no count
        assertThat(search("noodles", "").has("withoutFilters")).isFalse();
    }

    @Test
    void matchedWordsAreMarkedInNameChineseAndDescription() {
        JsonNode hit = search("mapo tofu", "").get("hits").get(0);
        assertThat(hit.get("highlight").get("name").asText())
                .isEqualTo(SearchService.MARK_START + "Mapo" + SearchService.MARK_END + " " + SearchService.MARK_START + "Tofu" + SearchService.MARK_END);
        assertThat(hit.get("highlight").get("description").asText()).contains(SearchService.MARK_START + "tofu");
        JsonNode zhHit = search("麻婆豆腐", "").get("hits").get(0);
        assertThat(zhHit.get("highlight").get("nameZh").asText()).contains(SearchService.MARK_START);
        // a pinyin match has nothing to mark in the visible text: no highlight rather than a wrong one
        assertThat(search("mpdf", "").get("hits").get(0).has("highlight")).isFalse();
        // browsing without words has no highlights at all
        http.get("/api/search?size=5", null).body().get("hits").forEach(h -> assertThat(h.has("highlight")).isFalse());
    }

    @Test
    void sortsByPriceRatingAndDistance() {
        var byPrice = search("noodles", "&sort=price&size=50").get("hits");
        List<Integer> prices = new ArrayList<>();
        byPrice.forEach(h -> prices.add(h.get("priceCents").asInt()));
        assertThat(prices).isSorted().hasSizeGreaterThan(5);

        var byRating = search("noodles", "&sort=rating&size=50").get("hits");
        List<Double> ratings = new ArrayList<>();
        byRating.forEach(h -> ratings.add(-h.get("rating").asDouble()));
        assertThat(ratings).isSorted();

        var near = search("noodles", "&sort=distance&lat=34.0911&lng=-118.1293&size=50").get("hits");
        List<Integer> distances = new ArrayList<>();
        near.forEach(h -> distances.add(h.get("distanceM").asInt()));
        assertThat(distances).isSorted().isNotEmpty();

        assertThat(http.get("/api/search?q=noodles&sort=distance", null).code()).isEqualTo("no_location");
        assertThat(http.get("/api/search?q=noodles&sort=cheapest", null).code()).isEqualTo("bad_sort");
    }

    @Test
    void postgresFallbackUsesSynonymsSortAndTheRelaxedPassToo() throws Exception {
        SearchService svc = ctx.getBean(SearchService.class);
        var boba = svc.postgres(new SearchService.Criteria("boba", List.of(), List.of(), null, 0, 5));
        assertThat(boba.degraded()).isTrue();
        assertThat(boba.hits()).extracting(SearchService.DishHit::nameZh).contains("珍珠奶茶");
        var relaxed = svc.postgres(new SearchService.Criteria("spicy fish", List.of(), List.of(), null, 0, 10));
        assertThat(relaxed.relaxed()).isTrue();
        assertThat(relaxed.hits()).extracting(SearchService.DishHit::nameZh).contains("水煮鱼");
        var cheap = svc.postgres(new SearchService.Criteria("noodles", List.of(), List.of(), null, 0, 50, null,
                SearchService.Sort.PRICE, false));
        assertThat(cheap.hits()).extracting(SearchService.DishHit::priceCents).isSorted();
    }

    @Autowired
    org.springframework.context.ApplicationContext ctx;

    @Test
    void suggestionsWhileTypingCoverDishesRestaurantsAndPinyin() {
        var r = http.get("/api/search/suggest?q=dump", null).body();
        assertThat(r.get("dishes").size()).isBetween(1, Suggestions.DISHES);
        r.get("dishes").forEach(d -> assertThat(d.get("name").asText().toLowerCase()).contains("dumpling"));

        assertThat(names(http.get("/api/search/suggest?q=lucky", null).body())).containsExactly("lucky-star-dumplings");
        assertThat(names(http.get("/api/search/suggest?q=cdys", null).body())).containsExactly("chengdu-night-market"); // initials
        assertThat(names(http.get("/api/search/suggest?q=" + URLEncoder.encode("成都", StandardCharsets.UTF_8), null).body()))
                .containsExactly("chengdu-night-market");
        assertThat(names(http.get("/api/search/suggest?q=night", null).body())).contains("chengdu-night-market");
        assertThat(names(http.get("/api/search/suggest?q=-", null).body())).isEmpty();
        assertThat(http.get("/api/search/suggest?q=" + "x".repeat(101), null).code()).isEqualTo("query_too_long");
    }

    static List<String> names(JsonNode suggest) {
        List<String> out = new ArrayList<>();
        suggest.get("restaurants").forEach(x -> out.add(x.get("slug").asText()));
        return out;
    }

    @Test
    void searchesThatMatchNothingAreCountedForAdminsButNotPartialTyping() {
        String word = "qqzx" + UUID.randomUUID().toString().substring(0, 6);
        search(word, "");
        search(word.toUpperCase() + "  ", "");                       // normalized: the same miss
        http.get("/api/search/suggest?q=" + word + "partial", null); // typing is never a miss
        search("noodles", "&tag=dessert");                           // nothing with these filters, but the words exist
        misses.flush();

        String admin = signIn("admin@example.com");
        var list = http.get("/api/admin/search-misses", admin);
        assertThat(list.status()).isEqualTo(200);
        Map<String, Integer> counts = new java.util.HashMap<>();
        list.body().forEach(m -> counts.put(m.get("query").asText(), m.get("misses").asInt()));
        assertThat(counts).containsEntry(word, 2).doesNotContainKeys(word + "partial", "noodles");
        assertThat(http.get("/api/admin/search-misses", signIn("customer@example.com")).status()).isEqualTo(403);
        assertThat(http.get("/api/admin/search-misses?days=90", admin).code()).isEqualTo("bad_range");
    }

    @Test
    void theMissBufferIsBounded() {
        for (int i = 0; i < SearchMisses.MAX_BUFFERED + 50; i++) {
            misses.record("flood-" + i);
        }
        assertThat(misses.buffered()).isEqualTo(SearchMisses.MAX_BUFFERED);
        misses.flush();
        assertThat(misses.buffered()).isZero();
        db.sql("delete from search_misses where query like 'flood-%'").update();
    }
}
