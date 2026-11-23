package com.wokflow.api.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.config.AppProperties;
import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.outbox.OutboxHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Keeps the Elasticsearch dish index in step with Postgres.
 *
 * <ul>
 *   <li>Incremental: outbox events re-index the changed dish or restaurant.
 *       Documents carry an external version (dish.version + restaurant.version,
 *       both only grow), so a late, older write can never overwrite a newer one.</li>
 *   <li>Full: a new physical index is built and the alias swapped atomically, so
 *       searches never see a half-built index. It runs at startup when the alias
 *       is missing or its document count disagrees with Postgres (e.g. the ES
 *       volume was lost), and is serialized across replicas with an advisory lock.</li>
 * </ul>
 */
@Component
public class SearchIndexer implements OutboxHandler {
    private static final Logger log = LoggerFactory.getLogger(SearchIndexer.class);
    private static final long LOCK_KEY = 0x77_6f_6b_5f_69_64_78L; // "wok_idx"
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);

    static final String DOCS_SQL = """
            select d.id, d.restaurant_id, r.slug, r.name as rname, r.name_zh as rname_zh, r.cuisine, r.neighborhood,
                   d.name, d.name_zh, d.description, d.tags, d.spice_level, d.price_cents, d.available,
                   r.accepting_orders, d.version + r.version as doc_version, coalesce(p.qty, 0) as popularity
            from dishes d
            join restaurants r on r.id = d.restaurant_id
            left join (
                select i.dish_id, sum(i.quantity) as qty
                from ledger_entries e join order_items i on i.order_id = e.order_id
                where e.kind = 'CAPTURE' and e.created_at > now() - interval '7 days'
                group by i.dish_id) p on p.dish_id = d.id
            """;

    private final ElasticsearchClient es;
    private final JdbcClient db;
    private final DataSource dataSource;
    private final CatalogRepository catalog;
    private final String alias;
    private final boolean reindexOnStartup;
    private final Clock clock;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final tools.jackson.databind.ObjectMapper json = tools.jackson.databind.json.JsonMapper.builder().build();

    public SearchIndexer(ElasticsearchClient es, JdbcClient db, DataSource dataSource, CatalogRepository catalog,
                         AppProperties props, Clock clock) {
        this.es = es;
        this.db = db;
        this.dataSource = dataSource;
        this.catalog = catalog;
        this.alias = props.search().indexAlias();
        this.reindexOnStartup = props.search().reindexOnStartup();
        this.clock = clock;
    }

    public String alias() { return alias; }

    /** The mapping file's hash, stored in the index's _meta: a changed mapping means a rebuild. */
    static String mappingHash() throws IOException {
        try (InputStream in = new ClassPathResource("search/dishes-index.json").getInputStream()) {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return java.util.HexFormat.of().formatHex(d).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    String liveMappingHash() throws IOException {
        var res = es.indices().getMapping(g -> g.index(alias));
        return res.mappings().values().stream().findFirst()
                .map(m -> m.mappings().meta() == null ? null : m.mappings().meta().get("mapping_hash"))
                .map(v -> v.to(String.class)).orElse(null);
    }

    public boolean isReady() { return ready.get(); }

    @Override
    public Set<String> topics() { return Set.of(Outbox.DISH_CHANGED, Outbox.RESTAURANT_CHANGED); }

    @Override
    public void handle(String topic, String aggregateId, JsonNode payload) throws IOException {
        if (!ready.get() && !aliasExists()) {
            return; // the full build will pick this change up
        }
        if (Outbox.DISH_CHANGED.equals(topic)) {
            index(docs("where d.id = ?", payload.get("dishId").asLong()));
        } else {
            index(docs("where d.restaurant_id = ?", payload.get("restaurantId").asLong()));
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStartup() {
        if (reindexOnStartup) {
            // Keep checking every 2 s for a while: if another replica is building the index, this one
            // should start using it as soon as the alias appears, not at the next 1-minute check.
            Thread.ofVirtual().name("search-bootstrap").start(() -> {
                for (int i = 0; i < 90 && !ready.get(); i++) {
                    ensureReady();
                    if (!ready.get()) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }
            });
        }
    }

    /** Self-heal: rebuild if the index is missing or out of step. Cheap when everything is fine. */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void ensureReady() {
        try {
            long dbCount = catalog.dishCount();
            if (aliasExists()) {
                es.indices().refresh(r -> r.index(alias));
                long esCount = es.count(c -> c.index(alias)).count();
                String live = liveMappingHash();
                if (esCount == dbCount && mappingHash().equals(live)) {
                    ready.set(true);
                    return;
                }
                if (!mappingHash().equals(live)) {
                    log.info("Search mapping changed ({} -> {}): rebuilding the index", live, mappingHash());
                } else {
                    log.warn("Search index has {} documents but Postgres has {} dishes: rebuilding", esCount, dbCount);
                }
            } else {
                log.info("Search index alias '{}' missing: building", alias);
            }
            reindexAll();
            ready.set(aliasExists()); // false if another replica is still building; the next check picks it up
        } catch (Exception e) {
            log.warn("Search index not ready yet ({}); search falls back to Postgres", e.toString());
        }
    }

    /** Builds a fresh physical index, swaps the alias, then replays anything changed meanwhile. */
    public void reindexAll() throws Exception {
        try (Connection lockConn = dataSource.getConnection()) {
            if (!tryLock(lockConn)) {
                log.info("Another replica is rebuilding the search index");
                return;
            }
            try {
                Instant started = clock.instant();
                String name = alias + "_" + SUFFIX.format(started);
                try (InputStream in = new ClassPathResource("search/dishes-index.json").getInputStream()) {
                    // _meta goes into the JSON itself: setting mappings on the builder would replace the file's mappings.
                    var tree = (tools.jackson.databind.node.ObjectNode) json.readTree(in);
                    ((tools.jackson.databind.node.ObjectNode) tree.get("mappings")).putObject("_meta").put("mapping_hash", mappingHash());
                    String mapping = json.writeValueAsString(tree);
                    es.indices().create(c -> c.index(name).withJson(new StringReader(mapping)));
                }
                List<DishDocument> all = docs("", null);
                for (int i = 0; i < all.size(); i += 500) {
                    bulk(name, all.subList(i, Math.min(all.size(), i + 500)));
                }
                es.indices().refresh(r -> r.index(name));
                List<String> old = aliasExists()
                        ? es.indices().getAlias(g -> g.name(alias)).aliases().keySet().stream().toList()
                        : List.of();
                es.indices().updateAliases(u -> {
                    u.actions(a -> a.add(x -> x.index(name).alias(alias)));
                    old.forEach(o -> u.actions(a -> a.remove(x -> x.index(o).alias(alias))));
                    return u;
                });
                for (String o : old) {
                    es.indices().delete(d -> d.index(o));
                }
                // Changes that went to the old index while we were building.
                index(docs("where d.updated_at >= ? or r.updated_at >= ?", java.sql.Timestamp.from(started)));
                log.info("Search index {} built with {} dishes", name, all.size());
            } finally {
                unlock(lockConn);
            }
        }
    }

    /** Re-indexes everything in place (popularity changes); versions are unchanged so external_gte accepts them. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT2M")
    public void refreshPopularity() {
        if (!ready.get()) {
            return;
        }
        try {
            index(docs("", null));
        } catch (Exception e) {
            log.warn("Popularity refresh failed: {}", e.toString());
        }
    }

    void index(List<DishDocument> docs) throws IOException {
        if (!docs.isEmpty()) {
            bulk(alias, docs);
        }
    }

    private void bulk(String index, List<DishDocument> docs) throws IOException {
        BulkRequest.Builder b = new BulkRequest.Builder();
        for (DishDocument d : docs) {
            b.operations(op -> op.index(i -> i.index(index).id(Long.toString(d.dishId())).document(d)
                    .version(d.version()).versionType(VersionType.ExternalGte)));
        }
        BulkResponse res = es.bulk(b.build());
        if (res.errors()) {
            var failed = res.items().stream().filter(i -> i.error() != null)
                    // a newer version already indexed is fine: that is the point of external versioning
                    .filter(i -> !"version_conflict_engine_exception".equals(i.error().type()))
                    .toList();
            if (!failed.isEmpty()) {
                throw new IOException("bulk index failed for " + failed.size() + " docs: " + failed.getFirst().error().reason());
            }
        }
    }

    boolean aliasExists() throws IOException {
        return es.indices().existsAlias(e -> e.name(alias)).value();
    }

    List<DishDocument> docs(String where, Object param) {
        var spec = db.sql(DOCS_SQL + where + " order by d.id");
        if (param != null) {
            spec = where.chars().filter(c -> c == '?').count() == 2 ? spec.params(param, param) : spec.param(param);
        }
        return spec.query((rs, n) -> doc(rs)).list();
    }

    static DishDocument doc(ResultSet rs) throws java.sql.SQLException {
        String nameZh = rs.getString("name_zh");
        Pinyin.Reading py = Pinyin.of(nameZh);
        return new DishDocument(rs.getLong("id"), rs.getLong("restaurant_id"), rs.getString("slug"),
                rs.getString("rname"), rs.getString("rname_zh"), rs.getString("cuisine"), rs.getString("neighborhood"),
                rs.getString("name"), nameZh, joined(rs.getString("name")), py.syllables(), py.joined(), py.initials(),
                rs.getString("description"),
                List.of((String[]) rs.getArray("tags").getArray()), rs.getInt("spice_level"), rs.getInt("price_cents"),
                rs.getBoolean("available"), rs.getBoolean("accepting_orders"), rs.getDouble("popularity"),
                rs.getLong("doc_version"));
    }

    /** "Biang Biang Noodles" -> "biangbiangnoodles", so a run-together English query still finds it. */
    static String joined(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static boolean tryLock(Connection c) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_try_advisory_lock(?)")) {
            ps.setLong(1, LOCK_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection c) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("select pg_advisory_unlock(?)")) {
            ps.setLong(1, LOCK_KEY);
            ps.execute();
        }
    }
}
