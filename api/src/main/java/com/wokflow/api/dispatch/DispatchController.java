package com.wokflow.api.dispatch;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.auth.Role;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.RateLimiter;
import com.wokflow.api.common.Tx;
import com.wokflow.api.geo.GeoPoint;
import com.wokflow.api.order.OrderService;
import com.wokflow.api.order.OrderViews.OrderView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DispatchController {
    public record Presence(boolean online, Double lat, Double lng) {}

    public record Location(@NotNull Double lat, @NotNull Double lng) {}

    private final RiderPresence presence;
    private final RiderTracker tracker;
    private final Dispatcher dispatcher;
    private final OrderService orders;
    private final RateLimiter limiter;
    private final Tx tx;

    public DispatchController(RiderPresence presence, RiderTracker tracker, Dispatcher dispatcher, OrderService orders,
                              RateLimiter limiter, Tx tx) {
        this.presence = presence;
        this.tracker = tracker;
        this.dispatcher = dispatcher;
        this.orders = orders;
        this.limiter = limiter;
        this.tx = tx;
    }

    /** Pins to the service region, so a broken GPS fix or a made-up position cannot attract offers. */
    static GeoPoint inRegion(Double lat, Double lng) {
        if (lat == null || lng == null || lat < 33.6 || lat > 34.5 || lng < -118.7 || lng > -117.5) {
            throw ApiException.badRequest("outside_region", "That position is outside the delivery region");
        }
        return new GeoPoint(lat, lng);
    }

    private static CurrentUser rider() {
        CurrentUser u = CurrentUser.require();
        u.requireApproved();
        return u;
    }

    @PostMapping("/api/rider/presence")
    Map<String, Object> presence(@RequestBody Presence req) {
        CurrentUser u = rider();
        if (req.online()) {
            tracker.ping(u.id(), inRegion(req.lat(), req.lng()));
        } else {
            presence.offline(u.id());
        }
        return Map.of("online", req.online());
    }

    @PostMapping("/api/rider/location")
    Map<String, Object> location(@Valid @RequestBody Location req) {
        CurrentUser u = rider();
        limiter.check("rider-location", Long.toString(u.id()), 120, Duration.ofMinutes(1));
        tracker.ping(u.id(), inRegion(req.lat(), req.lng()));
        return Map.of("ok", true);
    }

    @GetMapping("/api/rider/offers/current")
    ResponseEntity<Dispatcher.Offer> current() {
        return dispatcher.openOfferFor(rider().id()).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Accepting claims the order in the same transaction, so an offer and the job board cannot both win. */
    @PostMapping("/api/rider/offers/{id}/accept")
    OrderView accept(@PathVariable long id) {
        CurrentUser u = rider();
        var offer = dispatcher.openOfferFor(u.id()).filter(o -> o.id() == id)
                .orElseThrow(() -> ApiException.conflict("offer_gone", "This offer has expired or was withdrawn"));
        try {
            tx.runVoid(() -> {
                if (!dispatcher.respond(id, u.id(), "ACCEPTED")) {
                    throw ApiException.conflict("offer_gone", "This offer has expired or was withdrawn");
                }
                orders.claimInTransaction(offer.orderId(), u);
            });
        } catch (ApiException e) {
            if (e.status() == HttpStatus.CONFLICT && !"offer_gone".equals(e.code())) {
                dispatcher.cancelOpenOffer(offer.orderId()); // someone took it from the job board first
            }
            throw e;
        }
        return orders.get(offer.orderId(), u);
    }

    @PostMapping("/api/rider/offers/{id}/decline")
    Map<String, Object> decline(@PathVariable long id) {
        CurrentUser u = rider();
        var offer = dispatcher.openOfferFor(u.id()).filter(o -> o.id() == id)
                .orElseThrow(() -> ApiException.conflict("offer_gone", "This offer has expired or was withdrawn"));
        if (!dispatcher.respond(id, u.id(), "DECLINED")) {
            throw ApiException.conflict("offer_gone", "This offer has expired or was withdrawn");
        }
        dispatcher.dispatch(offer.orderId()); // straight to the next nearest rider
        return Map.of("declined", true);
    }

    /** Where the rider is, for the customer's order page (the live stream carries updates). */
    @GetMapping("/api/orders/{id}/rider")
    ResponseEntity<RiderTracker.Tracking> rider(@PathVariable UUID id) {
        CurrentUser u = CurrentUser.require();
        orders.get(id, u); // same visibility as the order itself
        if (u.role() != Role.CUSTOMER && u.role() != Role.ADMIN) {
            throw ApiException.forbidden();
        }
        return tracker.current(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

}
