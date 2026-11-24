package com.wokflow.api.dispatch;

import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.common.Tx;
import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.order.OrderRepository;
import com.wokflow.api.order.OrderStatus;
import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.outbox.OutboxHandler;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Offers each accepted order to the nearest free rider, one rider at a time.
 *
 * <ul>
 *   <li>Triggered by the order's status event (kitchen accepted it), by a declined or expired offer,
 *       and by a sweep for orders nobody has taken (riders who came online later).</li>
 *   <li>Candidates come from the Redis GEO set (nearest first, within {@link #SEARCH_KM} of the
 *       kitchen), minus riders already asked about this order, riders with an open offer and
 *       riders already carrying an order.</li>
 *   <li>The offer insert is guarded by partial unique indexes (one open offer per order, one per
 *       rider), so concurrent dispatchers on several replicas cannot double-offer.</li>
 *   <li>The open job board still works: whoever takes the order first wins, and taking it from
 *       the board cancels the open offer.</li>
 *   <li>If Redis is down there are simply no candidates: dispatch degrades to the job board.</li>
 * </ul>
 */
@Component
public class Dispatcher implements OutboxHandler {
    private static final Logger log = LoggerFactory.getLogger(Dispatcher.class);
    public static final Duration OFFER_TTL = Duration.ofSeconds(30);
    static final double SEARCH_KM = 6;

    public record Offer(long id, UUID orderId, long riderId, String status, int distanceM, Instant offeredAt, Instant expiresAt) {}

    private final JdbcClient db;
    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final RiderPresence presence;
    private final Outbox outbox;
    private final Tx tx;
    private final Clock clock;
    private final MeterRegistry meters;

    public Dispatcher(JdbcClient db, OrderRepository orders, CatalogRepository catalog, RiderPresence presence, Outbox outbox,
                      Tx tx, Clock clock, MeterRegistry meters) {
        this.db = db;
        this.orders = orders;
        this.catalog = catalog;
        this.presence = presence;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = clock;
        this.meters = meters;
    }

    @Override
    public Set<String> topics() { return Set.of(Outbox.ORDER_STATUS); }

    @Override
    public void handle(String topic, String aggregateId, JsonNode payload) {
        String status = payload.path("status").asText();
        boolean unassigned = payload.path("riderId").isNull() || payload.path("riderId").isMissingNode();
        if (OrderStatus.ACCEPTED.name().equals(status) && unassigned) {
            dispatch(UUID.fromString(payload.get("orderId").asText()));
        } else if (!unassigned) {
            cancelOpenOffer(UUID.fromString(payload.get("orderId").asText())); // taken from the job board
        }
    }

    /** Offers the order to the nearest eligible rider; returns the offer, or empty if nobody could take it. */
    public Optional<Offer> dispatch(UUID orderId) {
        var o = orders.find(orderId).orElse(null);
        if (o == null || o.riderId() != null || (o.status() != OrderStatus.ACCEPTED && o.status() != OrderStatus.READY)) {
            return Optional.empty();
        }
        Restaurant r = catalog.restaurant(o.restaurantId()).orElse(null);
        GeoPoint kitchen = r == null ? null : r.location();
        if (kitchen == null) {
            return Optional.empty();
        }
        List<RiderPresence.Nearby> candidates;
        try {
            candidates = presence.nearby(kitchen, SEARCH_KM, 20);
        } catch (RuntimeException e) {
            log.warn("Rider presence unavailable, order {} stays on the job board: {}", orderId, e.toString());
            return Optional.empty();
        }
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Set<Long> excluded = new HashSet<>(db.sql("select rider_id from dispatch_offers where order_id = ?").param(orderId)
                .query(Long.class).list());
        excluded.addAll(db.sql("""
                select rider_id from dispatch_offers where status = 'OFFERED' and rider_id = any(?)
                union select rider_id from orders where status in ('ACCEPTED', 'READY', 'PICKED_UP') and rider_id = any(?)""")
                .params(ids(candidates), ids(candidates)).query(Long.class).list());
        excluded.addAll(db.sql("select id from users where id = any(?) and (role <> 'RIDER' or not approved)")
                .param(ids(candidates)).query(Long.class).list());
        Instant now = clock.instant();
        for (var c : candidates) {
            if (excluded.contains(c.riderId())) {
                continue;
            }
            try {
                Offer offer = tx.run(() -> {
                    long id = db.sql("""
                            insert into dispatch_offers (order_id, rider_id, status, distance_m, offered_at, expires_at)
                            values (?, ?, 'OFFERED', ?, ?, ?) returning id""")
                            .params(orderId, c.riderId(), c.distanceM(), Timestamp.from(now), Timestamp.from(now.plus(OFFER_TTL)))
                            .query(Long.class).single();
                    outbox.publish(Outbox.DISPATCH_OFFER, orderId.toString(), Map.of("offerId", id, "orderId", orderId,
                            "riderId", c.riderId(), "distanceM", c.distanceM(), "expiresAt", now.plus(OFFER_TTL).toString(),
                            "restaurantId", r.id()));
                    return new Offer(id, orderId, c.riderId(), "OFFERED", c.distanceM(), now, now.plus(OFFER_TTL));
                });
                meters.counter("wokflow.dispatch.offers", "result", "offered").increment();
                return Optional.of(offer);
            } catch (DuplicateKeyException e) {
                // the order already has an open offer (another replica won) or this rider just got one
                if (openOffer(orderId).isPresent()) {
                    return Optional.empty();
                }
            }
        }
        meters.counter("wokflow.dispatch.offers", "result", "nobody").increment();
        return Optional.empty();
    }

    public Optional<Offer> openOffer(UUID orderId) {
        return db.sql("select " + COLS + " from dispatch_offers where order_id = ? and status = 'OFFERED'").param(orderId)
                .query(Dispatcher::map).optional();
    }

    public Optional<Offer> openOfferFor(long riderId) {
        return db.sql("select " + COLS + " from dispatch_offers where rider_id = ? and status = 'OFFERED'").param(riderId)
                .query(Dispatcher::map).optional();
    }

    /** Marks an offer answered (compare-and-set from OFFERED); false if it was no longer open. */
    public boolean respond(long offerId, long riderId, String status) {
        return db.sql("""
                update dispatch_offers set status = ?, responded_at = ?
                where id = ? and rider_id = ? and status = 'OFFERED' and expires_at > ?""")
                .params(status, Timestamp.from(clock.instant()), offerId, riderId, Timestamp.from(clock.instant()))
                .update() == 1;
    }

    void cancelOpenOffer(UUID orderId) {
        db.sql("update dispatch_offers set status = 'CANCELLED', responded_at = now() where order_id = ? and status = 'OFFERED'")
                .param(orderId).update();
    }

    /** Expired offers go to the next rider. SKIP LOCKED lets every replica run this without double work. */
    @Scheduled(fixedDelayString = "PT3S", initialDelayString = "PT10S")
    public void expire() {
        List<UUID> expired = tx.run(() -> db.sql("""
                update dispatch_offers set status = 'EXPIRED', responded_at = ?
                where id in (select id from dispatch_offers where status = 'OFFERED' and expires_at <= ?
                             order by expires_at limit 100 for update skip locked)
                returning order_id""")
                .params(Timestamp.from(clock.instant()), Timestamp.from(clock.instant())).query(UUID.class).list());
        for (UUID id : expired) {
            meters.counter("wokflow.dispatch.offers", "result", "expired").increment();
            dispatch(id);
        }
    }

    /** Orders nobody has taken yet get another try (a rider may have come online). */
    @Scheduled(fixedDelayString = "PT15S", initialDelayString = "PT20S")
    public void sweep() {
        List<UUID> waiting = db.sql("""
                select o.id from orders o
                where o.rider_id is null and o.status in ('ACCEPTED', 'READY') and o.updated_at > now() - interval '2 hours'
                  and not exists (select 1 from dispatch_offers d where d.order_id = o.id and d.status = 'OFFERED')
                order by o.updated_at limit 50""").query(UUID.class).list();
        for (UUID id : waiting) {
            dispatch(id);
        }
    }

    static final String COLS = "id, order_id, rider_id, status, distance_m, offered_at, expires_at";

    static Offer map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Offer(rs.getLong(1), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4), rs.getInt(5),
                rs.getTimestamp(6).toInstant(), rs.getTimestamp(7).toInstant());
    }

    private static Long[] ids(List<RiderPresence.Nearby> c) {
        return c.stream().map(RiderPresence.Nearby::riderId).toArray(Long[]::new);
    }
}
