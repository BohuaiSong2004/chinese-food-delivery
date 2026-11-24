package com.wokflow.api.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreMode;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public record Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size,
                           com.wokflow.api.geo.GeoPoint deliverTo) {
        public Criteria(String q, List<String> tags, List<String> cuisines, Integer maxSpice, int page, int size) {
            this(q, tags, cuisines, maxSpice, page, size, null);
        }
    }

    public record DishHit(long dishId, String name, String nameZh, String description, int priceCents,
                          List<String> tags, int spiceLevel, long restaurantId, String restaurantSlug,
                          String restaurantName, String restaurantNameZh, String cuisine, boolean acceptingOrders,
                          String image, Integer distanceM) {}

    public record Facet(String value, long count) {}

    public record Result(long total, List<DishHit> hits, Map<String, List<Facet>> facets, boolean degraded) {}

    private final ElasticsearchClient es;
    private final SearchIndexer indexer;
    private final JdbcClient db;
    private final Counter fallbacks;
    private final com.wokflow.api.images.ImageService images;

    public SearchService(ElasticsearchClient es, SearchIndexer indexer, JdbcClient db, MeterRegistry meters,
                         com.wokflow.api.images.ImageService images) {
        this.images = images;
        this.es = es;
        this.indexer = indexer;
        this.db = db;
        this.fallbacks = meters.counter("wokflow.search.fallback");
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

    Result elasticsearch(Criteria c) throws Exception {
        Query text = textQuery(c.q());
        List<Query> filters = new ArrayList<>();
        filters.add(Query.of(q -> q.term(t -> t.field("available").value(true))));
        filters.add(Query.of(q -> q.term(t -> t.field("listed").value(true))));
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
        if (c.deliverTo() != null) {
            // Only restaurants whose delivery area contains the customer's point.
            var point = co.elastic.clients.json.JsonData.of(Map.of("type", "point",
                    "coordinates", List.of(c.deliverTo().lng(), c.deliverTo().lat())));
            filters.add(Query.of(q -> q.geoShape(gs -> gs.field("delivery_area")
                    .shape(sh -> sh.shape(point).relation(co.elastic.clients.elasticsearch._types.GeoShapeRelation.Intersects)))));
        }
        Query scored = Query.of(q -> q.functionScore(fs -> fs
                .query(b -> b.bool(bool -> bool.must(text).filter(filters)))
                .functions(f -> f.weight(0.6).fieldValueFactor(v -> v.field("popularity")
                        .modifier(FieldValueFactorModifier.Log1p).missing(0.0)))
                // well-reviewed kitchens rank a little higher; the prior keeps a single 5-star review from dominating
                .functions(f -> f.weight(0.3).fieldValueFactor(v -> v.field("rating").missing(4.2)))
                .scoreMode(FunctionScoreMode.Sum)
                .boostMode(FunctionBoostMode.Sum)));

        SearchResponse<DishDocument> res = es.search(s -> {
            s.index(indexer.alias()).query(scored).from(c.page() * c.size()).size(c.size()).trackTotalHits(t -> t.enabled(true))
                    .aggregations("tags", a -> a.terms(t -> t.field("tags").size(20)))
                    .aggregations("cuisine", a -> a.terms(t -> t.field("cuisine").size(20)));
            if (c.q() == null || c.q().isBlank()) {
                s.sort(o -> o.field(f -> f.field("popularity").order(SortOrder.Desc)))
                 .sort(o -> o.field(f -> f.field("dish_id").order(SortOrder.Asc)));
            }
            return s;
        }, DishDocument.class);

        List<DishHit> hits = res.hits().hits().stream().map(Hit::source).map(d -> hit(d, c.deliverTo())).toList();
        long total = res.hits().total() == null ? hits.size() : res.hits().total().value();
        return new Result(total, hits, Map.of("tags", facet(res, "tags"), "cuisine", facet(res, "cuisine")), false);
    }

    /** Relevance: English with typos, Chinese characters, and pinyin in three spellings. */
    static Query textQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        String query = raw.strip();
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
        return Query.of(q -> q.bool(b -> b.should(should).minimumShouldMatch("1")));
    }

    private static List<Facet> facet(SearchResponse<?> res, String name) {
        var agg = res.aggregations().get(name);
        if (agg == null || !agg.isSterms()) {
            return List.of();
        }
        return agg.sterms().buckets().array().stream()
                .map((StringTermsBucket b) -> new Facet(b.key().stringValue(), b.docCount())).toList();
    }

    private DishHit hit(DishDocument d, com.wokflow.api.geo.GeoPoint to) {
        Integer distance = to == null || d.location() == null ? null
                : (int) Math.round(to.distanceTo(new com.wokflow.api.geo.GeoPoint(d.location().get("lat"), d.location().get("lon"))));
        return new DishHit(d.dishId(), d.name(), d.nameZh(), d.description(), d.priceCents(), d.tags(), d.spiceLevel(),
                d.restaurantId(), d.restaurantSlug(), d.restaurantName(), d.restaurantNameZh(), d.cuisine(),
                d.acceptingOrders(), images.url(d.image()), distance);
    }

    private static Integer distance(com.wokflow.api.geo.GeoPoint to, Double lat, Double lng) {
        return to == null || lat == null || lng == null ? null
                : (int) Math.round(to.distanceTo(new com.wokflow.api.geo.GeoPoint(lat, lng)));
    }

    /** Degraded mode: trigram similarity in Postgres. No pinyin, no facets, but the app keeps working. */
    Result postgres(Criteria c) {
        String q = c.q() == null ? "" : c.q().strip();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<DishHit> hits = db.sql("""
                select d.id, d.name, d.name_zh, d.description, d.price_cents, d.tags, d.spice_level, d.image_key, r.lat, r.lng,
                       r.id as rid, r.slug, r.name as rname, r.name_zh as rname_zh, r.cuisine, r.accepting_orders
                from dishes d join restaurants r on r.id = d.restaurant_id
                where d.available and d.archived_at is null and r.status = 'LIVE'
                  and (:q = '' or d.name ilike :like or d.name_zh like :like or r.name ilike :like
                       or similarity(d.name, :q) > 0.3)
                  and (cardinality(:tags::text[]) = 0 or d.tags @> :tags::text[])
                  and (cardinality(:cuisines::text[]) = 0 or r.cuisine = any(:cuisines::text[]))
                  and (:maxSpice::int is null or d.spice_level <= :maxSpice::int)
                  and (:lat::float8 is null or (r.lat is not null and 2 * 6371008.8 * asin(least(1, sqrt(
                        power(sin(radians(r.lat - :lat::float8) / 2), 2)
                        + cos(radians(:lat::float8)) * cos(radians(r.lat)) * power(sin(radians(r.lng - :lng::float8) / 2), 2))))
                        <= r.delivery_radius_km * 1000))
                order by case when :q = '' then 0 else similarity(d.name, :q) end desc, d.id
                limit :size offset :offset""")
                .param("q", q).param("like", like)
                .param("tags", c.tags().toArray(String[]::new)).param("cuisines", c.cuisines().toArray(String[]::new))
                .param("maxSpice", c.maxSpice()).param("size", c.size()).param("offset", c.page() * c.size())
                .param("lat", c.deliverTo() == null ? null : c.deliverTo().lat())
                .param("lng", c.deliverTo() == null ? null : c.deliverTo().lng())
                .query((rs, n) -> new DishHit(rs.getLong("id"), rs.getString("name"), rs.getString("name_zh"),
                        rs.getString("description"), rs.getInt("price_cents"),
                        List.of((String[]) rs.getArray("tags").getArray()), rs.getInt("spice_level"), rs.getLong("rid"),
                        rs.getString("slug"), rs.getString("rname"), rs.getString("rname_zh"), rs.getString("cuisine"),
                        rs.getBoolean("accepting_orders"), images.url(rs.getString("image_key")),
                        distance(c.deliverTo(), (Double) rs.getObject("lat"), (Double) rs.getObject("lng"))))
                .list();
        return new Result(hits.size(), hits, Map.of(), true);
    }
}
