package com.wokflow.api.recommend;

import com.wokflow.api.common.Tx;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * "Often ordered together", from paid orders of the last 90 days.
 *
 * <p>Precomputed into {@code dish_pairs} once an hour instead of joining order lines per request:
 * the cart asks on every change, and the answer barely moves within an hour. Measured at 300k
 * orders (docs/performance.md): the rebuild takes 2.5–8 s, a lookup about 1 ms, while computing one
 * popular dish's pairs per request took 180–280 ms. One replica rebuilds at a time
 * (transaction-scoped advisory lock); readers see the old rows until the rebuild commits.
 *
 * <p>Ranked by cosine similarity, together / sqrt(orders with a × orders with b), not by the raw
 * count: by count the most popular dish pairs with everything. A pair needs at least
 * {@link #MIN_TOGETHER} orders, so one customer's habit is not a recommendation.
 */
@Component
public class Pairings {
    private static final Logger log = LoggerFactory.getLogger(Pairings.class);
    private static final long LOCK_KEY = 0x77_6f_6b_5f_70_61_69L; // "wok_pai"
    static final int MIN_TOGETHER = 2;
    static final int LIMIT = 3;

    public record Pairing(long dishId, int together, double score) {}

    private final JdbcClient db;
    private final Tx tx;
    private final Timer rebuildTimer;

    public Pairings(JdbcClient db, Tx tx, MeterRegistry meters) {
        this.db = db;
        this.tx = tx;
        this.rebuildTimer = meters.timer("wokflow.pairings.rebuild");
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    void scheduled() {
        try {
            rebuild();
        } catch (RuntimeException e) {
            log.warn("Rebuilding dish pairings failed, keeping the previous ones: {}", e.toString());
        }
    }

    /** @return the number of pairs, or -1 when another replica is rebuilding right now */
    public int rebuild() {
        return rebuildTimer.record(() -> tx.run(() -> {
            Boolean locked = db.sql("select pg_try_advisory_xact_lock(:k)").param("k", LOCK_KEY).query(Boolean.class).single();
            if (!Boolean.TRUE.equals(locked)) {
                return -1;
            }
            db.sql("delete from dish_pairs").update();
            return db.sql("""
                    with lines as (
                        select distinct o.id as order_id, o.restaurant_id, i.dish_id
                        from orders o join order_items i on i.order_id = o.id
                        where o.status in ('PAID', 'ACCEPTED', 'READY', 'PICKED_UP', 'DELIVERED')
                          and o.created_at > now() - interval '90 days'),
                    per_dish as (select dish_id, count(*) as n from lines group by dish_id),
                    pairs as (
                        select a.restaurant_id, a.dish_id, b.dish_id as other_dish_id, count(*) as together
                        from lines a join lines b on b.order_id = a.order_id and b.dish_id <> a.dish_id
                        group by a.restaurant_id, a.dish_id, b.dish_id
                        having count(*) >= :min)
                    insert into dish_pairs (dish_id, other_dish_id, restaurant_id, together, score)
                    select p.dish_id, p.other_dish_id, p.restaurant_id, p.together,
                           p.together / sqrt(da.n::float8 * db.n::float8)
                    from pairs p
                    join per_dish da on da.dish_id = p.dish_id
                    join per_dish db on db.dish_id = p.other_dish_id""")
                    .param("min", MIN_TOGETHER).update();
        }));
    }

    /**
     * Dishes of the same restaurant most often ordered with those in {@code dishIds}, best first,
     * leaving out the dishes already chosen and any that cannot be ordered now.
     */
    public List<Pairing> forDishes(long restaurantId, List<Long> dishIds) {
        if (dishIds.isEmpty()) {
            return List.of();
        }
        Long[] ids = dishIds.toArray(Long[]::new);
        return db.sql("""
                select p.other_dish_id, sum(p.together)::int as together, sum(p.score) as score
                from dish_pairs p join dishes d on d.id = p.other_dish_id
                where p.restaurant_id = :rid and p.dish_id = any(:ids::bigint[]) and p.other_dish_id <> all(:ids::bigint[])
                  and d.available and d.archived_at is null
                group by p.other_dish_id
                order by score desc, together desc, p.other_dish_id
                limit :limit""")
                .param("rid", restaurantId).param("ids", ids).param("limit", LIMIT)
                .query((rs, n) -> new Pairing(rs.getLong("other_dish_id"), rs.getInt("together"), rs.getDouble("score")))
                .list();
    }
}
