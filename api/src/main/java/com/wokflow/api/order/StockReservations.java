package com.wokflow.api.order;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reserves limited stock outside the order transaction.
 *
 * <p>Each reservation is one autocommit statement that decrements the dish only
 * if enough is left and records a hold in the same statement. The row lock on a
 * popular dish therefore lasts for one statement on the server, never across a
 * round trip to the application. Under a rush that matters: when the API is
 * short of CPU, a transaction that has updated the row can wait tens of
 * milliseconds for its thread to send COMMIT, and every other order for that
 * dish queues behind it (a lock convoy). Statements run in ascending dish id
 * order and each locks a single row, so reservations cannot deadlock.
 */
@Component
public class StockReservations {
    private static final Logger log = LoggerFactory.getLogger(StockReservations.class);
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

    private final JdbcClient db;

    public StockReservations(JdbcClient db) { this.db = db; }

    public sealed interface Result {
        record Reserved() implements Result {}
        record SoldOut(long dishId) implements Result {}
        /** Another request with the same customer and Idempotency-Key is reserving or creating right now. */
        record InFlight() implements Result {}
    }

    /**
     * Reserves every limited dish or none. Must not run inside a transaction.
     */
    @Transactional(propagation = Propagation.NEVER)
    public Result reserve(UUID orderId, long customerId, String idempotencyKey, Map<Long, Integer> limitedQtyAscending) {
        for (var e : limitedQtyAscending.entrySet()) {
            int n;
            try {
                n = db.sql("""
                        with taken as (
                            update dishes set stock_remaining = stock_remaining - :qty
                            where id = :dish and daily_stock is not null and stock_remaining >= :qty
                            returning id)
                        insert into stock_holds (order_id, dish_id, quantity, customer_id, idempotency_key)
                        select :order, id, :qty, :customer, :key from taken""")
                        .param("qty", e.getValue()).param("dish", e.getKey()).param("order", orderId)
                        .param("customer", customerId).param("key", idempotencyKey)
                        .update();
            } catch (org.springframework.dao.DuplicateKeyException sameRequest) {
                release(orderId); // whatever this attempt already held; the statement itself was rolled back
                return new Result.InFlight();
            }
            if (n == 0) {
                release(orderId);
                return new Result.SoldOut(e.getKey());
            }
        }
        return new Result.Reserved();
    }

    /** True while some attempt of this request still holds stock (it may yet create the order). */
    public boolean held(long customerId, String idempotencyKey) {
        return db.sql("select exists (select 1 from stock_holds where customer_id = ? and idempotency_key = ?)")
                .params(customerId, idempotencyKey).query(Boolean.class).single();
    }

    /** Returns this order's held stock (compensation when the order was not created). Idempotent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID orderId) {
        List<long[]> holds = db.sql("delete from stock_holds where order_id = ? returning dish_id, quantity")
                .param(orderId).query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).list();
        holds.stream().sorted(java.util.Comparator.comparingLong(h -> h[0])).forEach(h ->
                db.sql("update dishes set stock_remaining = least(daily_stock, stock_remaining + ?) where id = ? and daily_stock is not null")
                        .params(h[1], h[0]).update());
    }

    /** Inside the order transaction: the order now owns the stock, so its holds go away with the same commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(UUID orderId) {
        db.sql("delete from stock_holds where order_id = ?").param(orderId).update();
    }

    /** Holds whose order never committed (process died between the two steps). */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public int sweepOrphans() {
        List<UUID> orphans = db.sql("""
                select distinct h.order_id from stock_holds h
                where h.created_at < now() - make_interval(secs => ?)
                  and not exists (select 1 from orders o where o.id = h.order_id)""")
                .param((double) ORPHAN_AFTER.toSeconds()).query(UUID.class).list();
        orphans.forEach(this::release);
        if (!orphans.isEmpty()) {
            log.warn("Returned stock from {} abandoned reservations", orphans.size());
        }
        return orphans.size();
    }
}
