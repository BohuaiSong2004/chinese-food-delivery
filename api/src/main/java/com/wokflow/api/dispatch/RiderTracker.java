package com.wokflow.api.dispatch;

import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.geo.DeliveryQuote;
import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.live.LiveHub;
import com.wokflow.api.order.OrderRepository;
import com.wokflow.api.order.OrderStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns rider location pings into what the customer sees: the rider's distance to where they are
 * heading next (the kitchen, then the customer) and an ETA, pushed on the order's live stream.
 * Positions are ephemeral (Redis, the live stream); nothing is written to Postgres per ping.
 */
@Component
public class RiderTracker {
    /** Customers do not need more than one position every few seconds. */
    static final long MIN_INTERVAL_MS = 3_000;

    public record Tracking(UUID orderId, double lat, double lng, String heading, int distanceM, int etaMinutes, Instant at) {}

    private final RiderPresence presence;
    private final LiveHub live;
    private final JdbcClient db;
    private final OrderRepository orders;
    private final CatalogRepository catalog;
    private final ObjectMapper json;
    private final Clock clock;
    private final Map<Long, Long> lastSent = new ConcurrentHashMap<>();

    public RiderTracker(RiderPresence presence, LiveHub live, JdbcClient db, OrderRepository orders, CatalogRepository catalog,
                        ObjectMapper json, Clock clock) {
        this.presence = presence;
        this.live = live;
        this.db = db;
        this.orders = orders;
        this.catalog = catalog;
        this.json = json;
        this.clock = clock;
    }

    public void ping(long riderId, GeoPoint at) {
        presence.update(riderId, at);
        long now = clock.millis();
        Long last = lastSent.get(riderId);
        if (last != null && now - last < MIN_INTERVAL_MS) {
            return;
        }
        lastSent.put(riderId, now);
        List<UUID> active = db.sql("select id from orders where rider_id = ? and status in ('ACCEPTED', 'READY', 'PICKED_UP')")
                .param(riderId).query(UUID.class).list();
        for (UUID id : active) {
            track(id, at).ifPresent(t -> live.publish("rider", List.of("order:" + id), json.writeValueAsString(t)));
        }
    }

    /** The rider's latest position for an order, as the customer sees it (also used on page load). */
    public Optional<Tracking> current(UUID orderId) {
        var o = orders.find(orderId).orElse(null);
        if (o == null || o.riderId() == null) {
            return Optional.empty();
        }
        return presence.position(o.riderId()).flatMap(p -> track(orderId, p.point()));
    }

    Optional<Tracking> track(UUID orderId, GeoPoint at) {
        var o = orders.find(orderId).orElse(null);
        if (o == null || o.riderId() == null) {
            return Optional.empty();
        }
        boolean toCustomer = o.status() == OrderStatus.PICKED_UP;
        GeoPoint target = toCustomer ? o.deliveryPoint() : catalog.restaurant(o.restaurantId()).map(r -> r.location()).orElse(null);
        if (target == null) {
            return Optional.empty();
        }
        double m = at.distanceTo(target);
        int eta = (int) Math.ceil(m / 1000 / DeliveryQuote.RIDER_KMH * 60);
        return Optional.of(new Tracking(orderId, round(at.lat()), round(at.lng()), toCustomer ? "customer" : "restaurant",
                (int) Math.round(m), eta, clock.instant()));
    }

    private static double round(double v) {
        return Math.round(v * 100_000) / 100_000.0; // ~1 m
    }
}
