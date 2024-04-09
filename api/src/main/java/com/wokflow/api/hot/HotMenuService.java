package com.wokflow.api.hot;

import com.wokflow.api.catalog.CatalogViews.CachedMenu;
import com.wokflow.api.catalog.CatalogViews.DishView;
import com.wokflow.api.catalog.CatalogViews.HotDish;
import com.wokflow.api.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * "Popular right now": per-restaurant sorted sets of dish order counts, one
 * set per hour, summed over the last {@code windowHours} with ZUNIONSTORE.
 * Counting is exactly-once per order (a SET NX marker in the same Lua script),
 * and the counters are rebuilt from the Postgres ledger whenever Redis has lost
 * them, so a Redis restart without persistence only costs a rebuild.
 */
@Service
public class HotMenuService {
    private static final Logger log = LoggerFactory.getLogger(HotMenuService.class);
    static final String PREFIX = "hot:v1:";
    static final String READY = PREFIX + "ready";
    static final Duration TOP_CACHE = Duration.ofSeconds(15);

    public record TrendingDish(long restaurantId, String restaurantSlug, String restaurantName, long dishId,
                               String name, String nameZh, int priceCents, long orders) {}

    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>("""
            if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[2]) then return 0 end
            for i = 4, #ARGV, 2 do
              redis.call('ZINCRBY', KEYS[2], ARGV[i + 1], ARGV[i])
              redis.call('ZINCRBY', KEYS[3], ARGV[i + 1], ARGV[3] .. ':' .. ARGV[i])
            end
            redis.call('EXPIRE', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[3], ARGV[1])
            return 1
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> TOP = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              local src = {}
              for i = 2, #KEYS do src[#src + 1] = KEYS[i] end
              redis.call('ZUNIONSTORE', KEYS[1], #src, unpack(src))
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[2]) - 1, 'WITHSCORES')
            """, List.class);

    private final StringRedisTemplate redis;
    private final JdbcClient db;
    private final Clock clock;
    private final int windowHours;
    private final int topN;

    public HotMenuService(StringRedisTemplate redis, JdbcClient db, Clock clock, AppProperties props) {
        this.redis = redis;
        this.db = db;
        this.clock = clock;
        this.windowHours = props.hotMenu().windowHours();
        this.topN = props.hotMenu().topN();
    }

    static long hour(Instant at) { return at.getEpochSecond() / 3600; }

    /** Counts a paid order once. Returns false if it was already counted. */
    public boolean record(UUID orderId, long restaurantId, Map<Long, Integer> quantities, Instant paidAt) {
        if (paidAt.isBefore(clock.instant().minus(Duration.ofHours(windowHours)))) {
            return false; // outside the window: nothing to show it in
        }
        long h = hour(paidAt);
        List<String> args = new ArrayList<>();
        args.add(Long.toString((windowHours + 2) * 3600L));
        args.add(Long.toString((windowHours + 2) * 3600L));
        args.add(Long.toString(restaurantId));
        quantities.forEach((dish, qty) -> {
            args.add(Long.toString(dish));
            args.add(Integer.toString(qty));
        });
        Long r = redis.execute(RECORD, List.of(PREFIX + "seen:" + orderId, PREFIX + "r:" + restaurantId + ":" + h,
                PREFIX + "all:" + h), args.toArray());
        return r != null && r == 1L;
    }

    /** Top dishes for a restaurant over the window. Empty (never an error) if Redis is unavailable. */
    public List<HotDish> top(long restaurantId, CachedMenu menu) {
        Map<Long, DishView> dishes = menu.categories().stream().flatMap(c -> c.dishes().stream())
                .collect(Collectors.toMap(DishView::id, Function.identity()));
        List<HotDish> out = new ArrayList<>();
        for (var e : ranked(PREFIX + "r:" + restaurantId + ":", topN).entrySet()) {
            DishView d = dishes.get(Long.parseLong(e.getKey()));
            if (d != null && d.available()) {
                out.add(new HotDish(d.id(), d.name(), d.nameZh(), d.priceCents(), e.getValue()));
            }
        }
        return out;
    }

    public List<TrendingDish> trending(int limit) {
        Map<String, Long> ranked = ranked(PREFIX + "all:", limit);
        if (ranked.isEmpty()) {
            return List.of();
        }
        List<Long> dishIds = ranked.keySet().stream().map(k -> Long.parseLong(k.substring(k.indexOf(':') + 1))).toList();
        Map<Long, TrendingDish> details = db.sql("""
                select d.id, d.restaurant_id, r.slug, r.name as rname, d.name, d.name_zh, d.price_cents
                from dishes d join restaurants r on r.id = d.restaurant_id where d.id = any(?) and d.available""")
                .param(dishIds.toArray(Long[]::new))
                .query((rs, n) -> new TrendingDish(rs.getLong("restaurant_id"), rs.getString("slug"), rs.getString("rname"),
                        rs.getLong("id"), rs.getString("name"), rs.getString("name_zh"), rs.getInt("price_cents"), 0))
                .list().stream().collect(Collectors.toMap(TrendingDish::dishId, Function.identity()));
        List<TrendingDish> out = new ArrayList<>();
        ranked.forEach((member, count) -> {
            TrendingDish t = details.get(Long.parseLong(member.substring(member.indexOf(':') + 1)));
            if (t != null) {
                out.add(new TrendingDish(t.restaurantId(), t.restaurantSlug(), t.restaurantName(), t.dishId(), t.name(),
                        t.nameZh(), t.priceCents(), count));
            }
        });
        return out;
    }

    /** member -> count, highest first. */
    Map<String, Long> ranked(String bucketPrefix, int n) {
        long now = hour(clock.instant());
        List<String> keys = new ArrayList<>();
        keys.add(bucketPrefix + "top" + windowHours + ":" + now); // cache key, rolls over every hour
        for (int i = 0; i < windowHours; i++) {
            keys.add(bucketPrefix + (now - i));
        }
        Map<String, Long> out = new LinkedHashMap<>();
        try {
            List<?> flat = redis.execute(TOP, keys, Long.toString(TOP_CACHE.toMillis()), Integer.toString(n));
            for (int i = 0; flat != null && i + 1 < flat.size(); i += 2) {
                out.put(flat.get(i).toString(), Math.round(Double.parseDouble(flat.get(i + 1).toString())));
            }
        } catch (RuntimeException e) {
            log.warn("Hot menu read failed: {}", e.toString());
        }
        return out;
    }

    /** The same answer computed in SQL, kept for the benchmark and the consistency test. */
    public Map<Long, Long> topFromSql(long restaurantId, int n) {
        return db.sql("""
                select i.dish_id, sum(i.quantity) as qty
                from ledger_entries e
                join orders o on o.id = e.order_id
                join order_items i on i.order_id = o.id
                where e.kind = 'CAPTURE' and o.restaurant_id = ? and e.created_at >= ?
                group by i.dish_id order by qty desc, i.dish_id limit ?""")
                .params(restaurantId, java.sql.Timestamp.from(Instant.ofEpochSecond((hour(clock.instant()) - windowHours + 1) * 3600)), n)
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getLong(2))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void rebuildIfLost() {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(READY))) {
                return;
            }
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + "rebuild-lock", "1", Duration.ofMinutes(2)))) {
                return;
            }
            int counted = rebuild();
            redis.opsForValue().set(READY, clock.instant().toString());
            redis.delete(PREFIX + "rebuild-lock");
            log.info("Hot menu counters rebuilt from the ledger ({} orders)", counted);
        } catch (RuntimeException e) {
            log.warn("Hot menu rebuild skipped: {}", e.toString());
        }
    }

    int rebuild() {
        record Line(UUID orderId, long restaurantId, Instant paidAt, long dishId, int qty) {}
        Instant from = Instant.ofEpochSecond((hour(clock.instant()) - windowHours + 1) * 3600);
        List<Line> lines = db.sql("""
                select e.order_id, o.restaurant_id, e.created_at, i.dish_id, i.quantity
                from ledger_entries e join orders o on o.id = e.order_id join order_items i on i.order_id = o.id
                where e.kind = 'CAPTURE' and e.created_at >= ? order by e.order_id, i.line_no""")
                .param(java.sql.Timestamp.from(from))
                .query((rs, n) -> new Line(rs.getObject(1, UUID.class), rs.getLong(2), rs.getTimestamp(3).toInstant(),
                        rs.getLong(4), rs.getInt(5))).list();
        Map<UUID, List<Line>> byOrder = lines.stream().collect(Collectors.groupingBy(Line::orderId, LinkedHashMap::new, Collectors.toList()));
        byOrder.forEach((id, ls) -> record(id, ls.getFirst().restaurantId(),
                ls.stream().collect(Collectors.toMap(Line::dishId, Line::qty, Integer::sum)), ls.getFirst().paidAt()));
        return byOrder.size();
    }
}
