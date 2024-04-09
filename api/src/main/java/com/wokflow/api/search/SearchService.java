package com.wokflow.api.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.wokflow.api.geo.GeoPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class SearchService {
    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Pattern LATIN = Pattern.compile("[a-zA-Z ]+");
    private static final Pattern HAN = Pattern.compile("\\p{IsHan}");
    /** Marks a highlighted span in {@link Highlight} strings. Private-use characters: never in a dish name. */
    public static final String MARK_START = "";
    public static final String MARK_END = "";
    /** Words that say nothing about the dish; ignored when only some words have to match. */
    static final Set<String> STOP = Set.of("and", "with", "the", "of", "in", "a", "an", "&");

    public enum Sort {
        RELEVANCE, PRICE, DISTANCE, RATING;

        public static Sort parse(String value) {
            try {
                return value == null || value.isBlank() ? RELEVANCE : valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw com.wokflow.api.common.ApiException.badRequest("bad_sort", "sort must be relevance, price, distance or rating");
            }
        }
    }

    /**
     * @param facets   compute tag and cuisine counts
     * @param asYouType a partial query from the suggestion box: no second, relaxed pass and not counted
     *                  as a miss ("kung p" is not a vocabulary gap)
     */
    public record Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size,
                           GeoPoint deliverTo, Sort sort, boolean facets, boolean asYouType) {
        public Criteria {
            sort = sort == null ? Sort.RELEVANCE : sort;
        }

        public Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size,
                        GeoPoint deliverTo, Sort sort, boolean facets) {
            this(q, tags, cuisines, maxSpice, page, size, deliverTo, sort, facets, false);
        }

        public Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size, GeoPoint deliverTo) {
            this(q, tags, cuisines, maxSpice, page, size, deliverTo, Sort.RELEVANCE, true);
        }

        static Criteria typing(String q, int size, GeoPoint deliverTo) {
            return new Criteria(q, List.of(), List.of(), null, 0, size, deliverTo, Sort.RELEVANCE, false, true);
        }

        public Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size) {
            this(q, tags, cuisines, maxSpice, page, size, null);
        }

        boolean hasText() { return q != null && !q.isBlank(); }

        boolean filtered() { return !tags.isEmpty() || !cuisines.isEmpty() || maxSpice != null; }

        Criteria withoutFilters() {
            return new Criteria(q, List.of(), List.of(), null, 0, size, deliverTo, sort, false);
        }

        Criteria anywhere() {
            return new Criteria(q, List.of(), List.of(), null, 0, size, null, sort, false);
        }
    }

    /** The matched words of each field, wrapped in {@link #MARK_START}/{@link #MARK_END}; null when that field did not match. */
    public record Highlight(String name, String nameZh, String description) {}

    public record DishHit(long dishId, String name, String nameZh, String description, int priceCents,
                          List<String> tags, int spiceLevel, long restaurantId, String restaurantSlug,
                          String restaurantName, String restaurantNameZh, String cuisine, boolean acceptingOrders,
                          String image, Integer distanceM, Double rating, Highlight highlight) {
        DishHit(long dishId, String name, String nameZh, String description, int priceCents, List<String> tags,
                int spiceLevel, long restaurantId, String restaurantSlug, String restaurantName, String restaurantNameZh,
                String cuisine, boolean acceptingOrders, String image, Integer distanceM) {
            this(dishId, name, nameZh, description, priceCents, tags, spiceLevel, restaurantId, restaurantSlug,
                    restaurantName, restaurantNameZh, cuisine, acceptingOrders, image, distanceM, null, null);
        }
    }

    public record Facet(String value, long count) {}

    /**
     * @param relaxed        nothing matched every word, so these hits match only some of them
     * @param withoutFilters when filters left nothing: how many dishes match the words without them
     */
    public record Result(long total, List<DishHit> hits, Map<String, List<Facet>> facets, boolean degraded,
                         boolean relaxed, Long withoutFilters) {
        public Result(long total, List<DishHit> hits, Map<String, List<Facet>> facets, boolean degraded) {
            this(total, hits, facets, degraded, false, null);
        }
    }

    private final ElasticsearchClient es;
    private final SearchIndexer indexer;
    private final JdbcClient db;
    private final Counter fallbacks;
    private final Counter relaxedCount;
    private final Counter zeroCount;
    private final com.wokflow.api.images.ImageService images;
    private final SearchMisses misses;

    public SearchService(ElasticsearchClient es, SearchIndexer indexer, JdbcClient db, MeterRegistry meters,
                         com.wokflow.api.images.ImageService images, SearchMisses misses) {
        this.images = images;
        this.es = es;
        this.indexer = indexer;
        this.db = db;
        this.misses = misses;
        this.fallbacks = meters.counter("wokflow.search.fallback");
        this.relaxedCount = meters.counter("wokflow.search.outcome", "result", "relaxed");
        this.zeroCount = meters.counter("wokflow.search.outcome", "result", "none");
    }

    /**
     * Elasticsearch first, Postgres when it is not ready, fails, or takes longer than ES_BUDGET.
     * The client's own socket timeout (10 s) suits bulk indexing, not a search box, so the query
     * runs on a virtual thread and is abandoned after the budget. A failure opens a short breaker so
     * a hung cluster costs one slow request per window, not one per search; the breaker closes
     * early if the abandoned query completes after all.
     */
    public Result search(Criteria c) {
        if (indexer.isReady() && System.nanoTime() - esSkipUntil >= 0) {
            var call = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return elasticsearch(c);
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, VIRTUAL);
            try {
                return call.get(ES_BUDGET.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
                if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.io.IOException) {
                    // unreachable or hanging: route around it for a while. The abandoned query keeps running;
                    // if it does come back, the cluster is slow but alive (cold caches), so stop routing around it.
                    esSkipUntil = System.nanoTime() + ES_BREAKER.toNanos();
                    call.thenRun(() -> esSkipUntil = System.nanoTime());
                    log.warn("Elasticsearch unreachable or slower than {} ms, using Postgres for up to {} s: {}",
                            ES_BUDGET.toMillis(), ES_BREAKER.toSeconds(), cause.toString());
                } else {
                    // it answered with an error (e.g. the index is being rebuilt): fall back for this request only
                    log.warn("Elasticsearch query failed, using Postgres for this request: {}", cause.toString());
                }
            }
        }
        fallbacks.increment();
        return postgres(c);
    }

    static final java.time.Duration ES_BUDGET = java.time.Duration.ofMillis(1500);
    static final java.time.Duration ES_BREAKER = java.time.Duration.ofSeconds(10);
    private static final java.util.concurrent.Executor VIRTUAL = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private volatile long esSkipUntil = System.nanoTime();

    /**
     * Every word has to match first. Only when that finds nothing (rare: 8 of 45 held-out queries
     * before this existed, e.g. "spicy fish" where no dish name says "spicy") does a second query
     * accept dishes matching some of the words, flagged {@code relaxed} so the page can say so.
     */
    Result elasticsearch(Criteria c) throws Exception {
        Run strict = run(c, textQuery(c.q()));
        if (strict.result().total() > 0 || !c.hasText() || c.asYouType()) {
            return strict.result();
        }
        Long withoutFilters = c.filtered() ? strict.withoutFilters() : null;
        if (withoutFilters != null && withoutFilters > 0) {
            // the words are fine, the filters exclude them: offer to drop the filters rather than guess
            zeroCount.increment();
            return new Result(0, List.of(), strict.result().facets(), false, false, withoutFilters);
        }
        boolean known = c.deliverTo() != null && count(c.anywhere()) > 0; // nothing nearby, but elsewhere
        if (!known) {
            misses.record(c.q()); // the words match nothing anywhere in the catalog: a vocabulary gap
        }
        Result relaxed = run(c, relaxedQuery(c.q())).result();
        if (relaxed.total() > 0) {
            relaxedCount.increment();
            return new Result(relaxed.total(), relaxed.hits(), relaxed.facets(), false, true, withoutFilters);
        }
        zeroCount.increment();
        return new Result(0, List.of(), strict.result().facets(), false, false, withoutFilters);
    }

    private long count(Criteria c) throws Exception {
        List<Query> all = new ArrayList<>(baseFilters(c));
        all.addAll(choiceFilters(c));
        return es.count(s -> s.index(indexer.alias()).query(Query.of(q -> q.bool(b -> b.must(textQuery(c.q())).filter(all)))))
                .count();
    }

    /** Always applied: orderable dishes of live restaurants, and the delivery area when a point is given. */
    private static List<Query> baseFilters(Criteria c) {
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("available").value(true))));
        filters.add(Query.of(q -> q.term(t -> t.field("listed").value(true))));
        if (c.deliverTo() != null) {
            // Only restaurants whose delivery area contains the customer's point.
            var point = co.elastic.clients.json.JsonData.of(Map.of("type", "point",
                    "coordinates", List.of(c.deliverTo().lng(), c.deliverTo().lat())));
            filters.add(Query.of(q -> q.geoShape(gs -> gs.field("delivery_area")
                    .shape(sh -> sh.shape(point).relation(co.elastic.clients.elasticsearch._types.GeoShapeRelation.Intersects)))));
        }
        return filters;
    }

    /** The sidebar choices: tags, cuisines, spice. */
    private static List<Query> choiceFilters(Criteria c) {
        List<Query> filters = new ArrayList<>();
        for (String tag : c.tags()) {
            filters.add(Query.of(q -> q.term(t -> t.field("tags").value(tag))));
        }
        if (!c.cuisines().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t.field("cuisine")
                    .terms(v -> v.value(c.cuisines().stream().map(FieldValue::of).toList())))));
        }
        if (c.maxSpice() != null) {
            filters.add(Query.of(q -> q.range(r -> r.number(n -> n.field("spice_level").lte((double) c.maxSpice())))));
        }
        return filters;
    }

    /** @param withoutFilters how many dishes the query matches without the sidebar choices (only when there are some) */
    private record Run(Result result, Long withoutFilters) {}

    /**
     * One request. With sidebar choices, they are a post_filter: the hits are filtered, while an
     * aggregation sees what the words alone match, so "N dishes without the filters" costs no second
     * request (a separate count made filtered searches that found nothing ~30% slower under load).
     * The facets are computed inside a filter aggregation with the same choices, so their counts
     * still describe the filtered results.
     */
    private Run run(Criteria c, Query text) throws Exception {
        List<Query> choices = choiceFilters(c);
        Query scored = Query.of(q -> q.functionScore(fs -> fs
                .query(b -> b.bool(bool -> bool.must(text).filter(baseFilters(c))))
                .functions(f -> f.weight(0.6).fieldValueFactor(v -> v.field("popularity")
                        .modifier(FieldValueFactorModifier.Log1p).missing(0.0)))
                // well-reviewed kitchens rank a little higher; the prior keeps a single 5-star review from dominating
                .functions(f -> f.weight(0.3).fieldValueFactor(v -> v.field("rating").missing(4.2)))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Sum)));
        Query chosen = Query.of(q -> q.bool(b -> b.filter(choices)));

        SearchResponse<DishDocument> res = es.search(s -> {
            s.index(indexer.alias()).query(scored).from(c.page() * c.size()).size(c.size())
                    .trackTotalHits(t -> t.enabled(true));
            if (!choices.isEmpty()) {
                s.postFilter(chosen)
                 .aggregations("everything", a -> a.filter(f -> f.matchAll(m -> m))); // the words alone, before post_filter
            }
            if (c.facets()) {
                s.aggregations("chosen", a -> a.filter(chosen)
                        .aggregations("tags", t -> t.terms(x -> x.field("tags").size(20)))
                        .aggregations("cuisine", t -> t.terms(x -> x.field("cuisine").size(20))));
            }
            for (SortOptions o : sortOptions(c)) {
                s.sort(o);
            }
            return s;
        }, DishDocument.class);

        Highlighter marks = Highlighter.of(c.q());
        List<DishHit> hits = res.hits().hits().stream().map(h -> hit(h.source(), c.deliverTo(), marks)).toList();
        long total = res.hits().total() == null ? hits.size() : res.hits().total().value();
        Map<String, List<Facet>> facets = Map.of();
        Long withoutFilters = null;
        var chosenAgg = res.aggregations().get("chosen");
        if (chosenAgg != null && chosenAgg.isFilter()) {
            var sub = chosenAgg.filter().aggregations();
            facets = Map.of("tags", facet(sub, "tags"), "cuisine", facet(sub, "cuisine"));
        }
        var everything = res.aggregations().get("everything");
        if (everything != null && everything.isFilter()) {
            withoutFilters = everything.filter().docCount();
        }
        return new Run(new Result(total, hits, facets, false), withoutFilters);
    }

    private static List<SortOptions> sortOptions(Criteria c) {
        List<SortOptions> sort = new ArrayList<>();
        switch (c.sort()) {
            case PRICE -> sort.add(SortOptions.of(o -> o.field(f -> f.field("price_cents").order(SortOrder.Asc))));
            case RATING -> sort.add(SortOptions.of(o -> o.field(f -> f.field("rating").order(SortOrder.Desc))));
            case DISTANCE -> {
                if (c.deliverTo() != null) {
                    sort.add(SortOptions.of(o -> o.geoDistance(g -> g.field("location")
                            .location(l -> l.latlon(ll -> ll.lat(c.deliverTo().lat()).lon(c.deliverTo().lng())))
                            .order(SortOrder.Asc))));
                }
            }
            case RELEVANCE -> {
                if (!c.hasText()) {
                    sort.add(SortOptions.of(o -> o.field(f -> f.field("popularity").order(SortOrder.Desc))));
                }
            }
        }
        if (!sort.isEmpty() && c.hasText()) {
            sort.add(SortOptions.of(o -> o.score(s -> s.order(SortOrder.Desc)))); // ties: the better match first
        }
        if (!sort.isEmpty()) {
            sort.add(SortOptions.of(o -> o.field(f -> f.field("dish_id").order(SortOrder.Asc)))); // stable pages
        }
        return sort;
    }

    /** Relevance: English with typos, Chinese characters, and pinyin in three spellings. */
    static Query textQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        String query = raw.strip();
        List<Query> should = clauses(query);
        // Other names for the dish ("boba" → "bubble milk tea"): searched too, ranked a little below the words typed.
        for (String variant : Synonyms.variants(query)) {
            should.add(variantClause(variant));
        }
        return Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }

    /** Second chance after nothing matched every word: any of the meaningful words, more of them ranking higher. */
    static Query relaxedQuery(String raw) {
        List<String> phrases = new ArrayList<>();
        phrases.add(raw.strip());
        phrases.addAll(Synonyms.variants(raw));
        List<Query> should = new ArrayList<>();
        for (String phrase : phrases) {
            String words = String.join(" ", meaningfulWords(phrase));
            if (words.isEmpty()) {
                continue;
            }
            should.add(Query.of(q -> q.multiMatch(m -> m.query(words)
                    .fields("name^4", "name.stem^3", "restaurant_name", "description")
                    .type(TextQueryType.MostFields).fuzziness("AUTO").prefixLength(1).operator(Operator.Or))));
        }
        return should.isEmpty() ? Query.of(q -> q.matchNone(m -> m))
                : Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }

    static List<String> meaningfulWords(String phrase) {
        List<String> out = new ArrayList<>();
        for (String w : phrase.toLowerCase(Locale.ROOT).split("[\\s,]+")) {
            if (!w.isBlank() && !STOP.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    /**
     * Another name for the dish, ranked a little below the words typed. Exact words only: the variant
     * comes from synonyms.txt, not from a user's fingers, so it needs no fuzziness or pinyin clauses
     * (with them, queries that have synonyms cost 2–5× as much in Elasticsearch).
     */
    private static Query variantClause(String variant) {
        List<Query> should = new ArrayList<>();
        should.add(Query.of(q -> q.multiMatch(m -> m.query(variant)
                .fields("name^4", "name.stem^3", "description").type(TextQueryType.BestFields).operator(Operator.And))));
        if (HAN.matcher(variant).find()) {
            should.add(Query.of(q -> q.match(m -> m.field("name_zh").query(variant).boost(5f))));
            should.add(Query.of(q -> q.match(m -> m.field("name_zh.uni").query(variant).operator(Operator.And).boost(2f))));
        }
        return Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1").boost(0.8f)));
    }

    /** English words (typos allowed, word starts) and Chinese characters. */
    private static List<Query> wordClauses(String query) {
        List<Query> should = new ArrayList<>();
        should.add(Query.of(q -> q.multiMatch(m -> m.query(query)
                .fields("name^4", "name.stem^3", "restaurant_name^2", "description")
                .type(TextQueryType.BestFields).fuzziness("AUTO").prefixLength(1).operator(Operator.And))));
        should.add(Query.of(q -> q.match(m -> m.field("name.prefix").query(query).operator(Operator.And).boost(2f))));
        if (HAN.matcher(query).find()) {
            should.add(Query.of(q -> q.match(m -> m.field("name_zh").query(query).boost(5f))));
            should.add(Query.of(q -> q.match(m -> m.field("name_zh.uni").query(query).operator(Operator.And).boost(2f))));
            should.add(Query.of(q -> q.match(m -> m.field("restaurant_name_zh").query(query).boost(2f))));
        }
        return should;
    }

    /** {@link #wordClauses} plus pinyin: spaced, joined, prefix, typo, n-grams and initials. */
    private static List<Query> clauses(String query) {
        List<Query> should = wordClauses(query);
        if (LATIN.matcher(query).matches()) {
            String compact = query.replace(" ", "").toLowerCase(Locale.ROOT);
            should.add(Query.of(q -> q.match(m -> m.field("pinyin").query(query).operator(Operator.And).boost(3f))));
            should.add(Query.of(q -> q.prefix(p -> p.field("pinyin_joined").value(compact).boost(3f))));
            if (compact.length() >= 4) {
                should.add(Query.of(q -> q.fuzzy(f -> f.field("pinyin_joined").value(compact).fuzziness("AUTO").boost(2f))));
            }
            if (compact.length() >= 3 && compact.length() <= 12) {
                should.add(Query.of(q -> q.match(m -> m.field("pinyin_joined.grams").query(compact).boost(1.5f))));
            }
            if (compact.length() >= 2 && compact.length() <= 8) {
                should.add(Query.of(q -> q.term(t -> t.field("pinyin_initials").value(compact).boost(4f))));
                // initials of part of the name: "xlb" inside 鲜肉小笼包 (xrxlb)
                should.add(Query.of(q -> q.match(m -> m.field("pinyin_initials.grams").query(compact).boost(1.5f))));
            }
            if (compact.length() >= 4) {
                should.add(Query.of(q -> q.prefix(p -> p.field("name_joined").value(compact).boost(2f))));
            }
        }
        return should;
    }

    private static List<Facet> facet(Map<String, co.elastic.clients.elasticsearch._types.aggregations.Aggregate> aggs, String name) {
        var agg = aggs.get(name);
        if (agg == null || !agg.isSterms()) {
            return List.of();
        }
        return agg.sterms().buckets().array().stream()
                .map((StringTermsBucket b) -> new Facet(b.key().stringValue(), b.docCount())).toList();
    }

    private DishHit hit(DishDocument d, GeoPoint to, Highlighter marks) {
        Integer distance = to == null || d.location() == null ? null
                : (int) Math.round(to.distanceTo(new GeoPoint(d.location().get("lat"), d.location().get("lon"))));
        return new DishHit(d.dishId(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.restaurantId(), d.restaurantSlug(), d.restaurantName(), d.restaurantNameZh(), d.cuisine(),
                d.acceptingOrders(), images.url(d.image()), distance, d.rating(), highlight(marks, d.name(), d.nameZh(), d.description()));
    }

    static Highlight highlight(Highlighter marks, String name, String nameZh, String description) {
        if (marks.isEmpty()) {
            return null;
        }
        Highlight h = new Highlight(marks.mark(name), marks.mark(nameZh), marks.mark(description));
        return h.name() == null && h.nameZh() == null && h.description() == null ? null : h;
    }

    private static Integer distance(GeoPoint to, Double lat, Double lng) {
        return to == null || lat == null || lng == null ? null : (int) Math.round(to.distanceTo(new GeoPoint(lat, lng)));
    }

    private static String contains(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /**
     * Degraded mode: LIKE and trigram similarity in Postgres. No pinyin and no facets, but the app
     * keeps working. Synonyms apply, and when every word together finds nothing, any
     * meaningful word may match (flagged relaxed), as with Elasticsearch.
     */
    Result postgres(Criteria c) {
        String q = c.q() == null ? "" : c.q().strip();
        List<String> phrases = new ArrayList<>();
        phrases.add(q);
        phrases.addAll(Synonyms.variants(q));
        List<DishHit> hits = postgres(c, q, phrases);
        if (hits.isEmpty() && !q.isEmpty() && !c.asYouType()) {
            List<String> words = new ArrayList<>();
            for (String p : phrases) {
                meaningfulWords(p).stream().filter(w -> w.length() >= 3 || HAN.matcher(w).find()).forEach(words::add);
            }
            if (words.size() > 1) {
                List<DishHit> relaxed = postgres(c, q, words.stream().distinct().toList());
                if (!relaxed.isEmpty()) {
                    return new Result(relaxed.size(), relaxed, Map.of(), true, true, null);
                }
            }
        }
        return new Result(hits.size(), hits, Map.of(), true);
    }

    private List<DishHit> postgres(Criteria c, String q, List<String> phrases) {
        Highlighter marks = Highlighter.of(q);
        String[] likes = phrases.stream().map(SearchService::contains).toArray(String[]::new);
        String order = switch (c.sort()) {
            case PRICE -> "d.price_cents, d.id";
            case RATING -> "(r.rating_sum + 4.2 * 5) / (r.rating_count + 5) desc, d.id";
            case DISTANCE -> c.deliverTo() == null ? "d.id" : "power(r.lat - :lat::float8, 2) + power((r.lng - :lng::float8) * cos(radians(:lat::float8)), 2), d.id";
            case RELEVANCE -> "case when :q = '' then 0 else similarity(d.name, :q) end desc, d.id";
        };
        return db.sql("""
                select d.id, d.name, d.name_zh, d.description, d.price_cents, d.tags, d.spice_level, d.image_key, r.lat, r.lng,
                       r.id as rid, r.slug, r.name as rname, r.name_zh as rname_zh, r.cuisine, r.accepting_orders
                from dishes d join restaurants r on r.id = d.restaurant_id
                where d.available and d.archived_at is null and r.status = 'LIVE'
                  and (:q = '' or d.name ilike any(:likes::text[]) or d.name_zh like any(:likes::text[])
                       or r.name ilike any(:likes::text[]) or similarity(d.name, :q) > 0.3)
                  and (cardinality(:tags::text[]) = 0 or d.tags @> :tags::text[])
                  and (cardinality(:cuisines::text[]) = 0 or r.cuisine = any(:cuisines::text[]))
                  and (:maxSpice::int is null or d.spice_level <= :maxSpice::int)
                  and (:lat::float8 is null or (r.lat is not null and 2 * 6371008.8 * asin(least(1, sqrt(
                        power(sin(radians(r.lat - :lat::float8) / 2), 2)
                        + cos(radians(:lat::float8)) * cos(radians(r.lat)) * power(sin(radians(r.lng - :lng::float8) / 2), 2))))
                        <= r.delivery_radius_km * 1000))
                order by %s
                limit :size offset :offset""".formatted(order))
                .param("q", q).param("likes", likes)
                .param("tags", c.tags().toArray(String[]::new)).param("cuisines", c.cuisines().toArray(String[]::new))
                .param("maxSpice", c.maxSpice()).param("size", c.size()).param("offset", c.page() * c.size())
                .param("lat", c.deliverTo() == null ? null : c.deliverTo().lat())
                .param("lng", c.deliverTo() == null ? null : c.deliverTo().lng())
                .query((rs, n) -> new DishHit(rs.getLong("id"), rs.getString("name"), rs.getString("name_zh"),
                        rs.getString("description"), rs.getInt("price_cents"),
                        List.of((String[]) rs.getArray("tags").getArray()), rs.getInt("spice_level"), rs.getLong("rid"),
                        rs.getString("slug"), rs.getString("rname"), rs.getString("rname_zh"), rs.getString("cuisine"),
                        rs.getBoolean("accepting_orders"), images.url(rs.getString("image_key")),
                        distance(c.deliverTo(), (Double) rs.getObject("lat"), (Double) rs.getObject("lng")), null,
                        highlight(marks, rs.getString("name"), rs.getString("name_zh"), rs.getString("description"))))
                .list();
    }
}
