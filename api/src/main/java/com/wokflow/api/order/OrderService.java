package com.wokflow.api.order;

import com.wokflow.api.auth.CurrentUser;
import com.wokflow.api.auth.Role;
import com.wokflow.api.catalog.CatalogRepository;
import com.wokflow.api.catalog.Dish;
import com.wokflow.api.catalog.Restaurant;
import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.RateLimiter;
import com.wokflow.api.common.Tx;
import com.wokflow.api.config.AppProperties;
import com.wokflow.api.order.OrderRepository.Order;
import com.wokflow.api.order.OrderViews.Line;
import com.wokflow.api.order.OrderViews.OrderView;
import com.wokflow.api.order.OrderViews.RestaurantRef;
import com.wokflow.api.order.OrderViews.StatusEvent;
import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.payment.PaymentService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class OrderService {
    public record ItemRequest(long dishId, int quantity) {}

    public record PlaceOrder(long restaurantId, List<ItemRequest> items, String deliveryAddress, String deliveryNotes) {}

    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final StockReservations stock;
    private final CatalogRepository catalog;
    private final PaymentService payments;
    private final Outbox outbox;
    private final RateLimiter limiter;
    private final com.wokflow.api.common.Bulkhead placements;
    private final Tx tx;
    private final JdbcClient db;
    private final Clock clock;
    private final AppProperties props;

    public OrderService(OrderRepository orders, OrderTransitions transitions, StockReservations stock, CatalogRepository catalog,
                        PaymentService payments, Outbox outbox, RateLimiter limiter, Tx tx, JdbcClient db, Clock clock,
                        AppProperties props, io.micrometer.core.instrument.MeterRegistry meters) {
        this.placements = new com.wokflow.api.common.Bulkhead("order-placement", props.orders().placementConcurrency(),
                props.orders().placementWait(), meters);
        this.orders = orders;
        this.transitions = transitions;
        this.stock = stock;
        this.catalog = catalog;
        this.payments = payments;
        this.outbox = outbox;
        this.limiter = limiter;
        this.tx = tx;
        this.db = db;
        this.clock = clock;
        this.props = props;
    }

    public record Placed(OrderView order, boolean created) {}

    private record Outcome(UUID id, boolean created) {}

    /** Same key, same body: return the original order. Same key, different body: refuse. */
    private static Outcome replay(Order existing, String hash) {
        if (!existing.requestHash().equals(hash)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                    "This Idempotency-Key was already used for a different order");
        }
        return new Outcome(existing.id(), false);
    }

    /**
     * Places an order: prices come from the database, never the client; limited
     * stock is reserved with conditional decrements in ascending dish id order
     * (so concurrent orders sharing dishes cannot deadlock); and the
     * Idempotency-Key makes a retried request return the original order.
     * The payment intent is created after commit, without holding any lock.
     */
    public Placed place(CurrentUser user, PlaceOrder req, String idempotencyKey) {
        if (user.role() != Role.CUSTOMER) {
            throw new ApiException(HttpStatus.FORBIDDEN, "customers_only", "Only customer accounts can place orders");
        }
        limiter.check("orders", Long.toString(user.id()), 30, Duration.ofMinutes(1));
        Map<Long, Integer> qty = new TreeMap<>(); // ascending dish id: the order stock is reserved in
        for (ItemRequest i : req.items()) {
            qty.merge(i.dishId(), i.quantity(), Integer::sum);
        }
        if (qty.values().stream().anyMatch(q -> q > 50)) {
            throw ApiException.badRequest("too_many", "At most 50 of one dish per order");
        }
        String hash = requestHash(req.restaurantId(), qty, req.deliveryAddress(), req.deliveryNotes());

        Outcome outcome = placements.run(() -> {
            var existing = orders.findByKey(user.id(), idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), hash);
            }
            Priced priced = price(req, qty);
            UUID id = UUID.randomUUID();
            Map<Long, Integer> limited = new TreeMap<>();
            qty.forEach((dish, n) -> {
                if (priced.dishes().get(dish).limited()) {
                    limited.put(dish, n);
                }
            });
            var soldOut = stock.reserve(id, limited);
            if (soldOut.isPresent()) {
                throw ApiException.conflict("sold_out", priced.dishes().get(soldOut.get()).name() + " just sold out");
            }
            try {
                return tx.run(() -> create(user, req, idempotencyKey, hash, id, priced));
            } catch (Replayed r) {
                stock.release(id);
                return r.outcome;
            } catch (RuntimeException e) {
                stock.release(id); // the order did not commit: give the stock back now
                throw e;
            }
        });
        payments.ensureCheckout(outcome.id());
        return new Placed(view(orders.find(outcome.id()).orElseThrow()), outcome.created());
    }

    private record Priced(Restaurant restaurant, Map<Long, Dish> dishes, List<Line> lines, int subtotal, int commission, Instant now) {}

    /** Validates against the database (prices come from here, never from the client). Read-only. */
    private Priced price(PlaceOrder req, Map<Long, Integer> qty) {
        Restaurant r = catalog.restaurant(req.restaurantId()).orElseThrow(() -> ApiException.notFound("Restaurant"));
        Instant now = clock.instant();
        if (!r.acceptingOrders()) {
            throw ApiException.conflict("restaurant_paused", r.name() + " is not taking orders right now");
        }
        if (!r.isOpenAt(now)) {
            throw ApiException.conflict("restaurant_closed", r.name() + " is closed; it opens at " + r.nextOpening(now));
        }
        Map<Long, Dish> dishes = catalog.dishesById(qty.keySet()).stream()
                .collect(Collectors.toMap(Dish::id, Function.identity()));
        int subtotal = 0;
        List<Line> lines = new java.util.ArrayList<>();
        for (var e : qty.entrySet()) {
            Dish d = dishes.get(e.getKey());
            if (d == null || d.restaurantId() != r.id()) {
                throw ApiException.badRequest("unknown_dish", "Dish " + e.getKey() + " is not on this menu");
            }
            if (!d.available()) {
                throw ApiException.conflict("dish_unavailable", d.name() + " is not available right now");
            }
            subtotal += d.priceCents() * e.getValue();
            lines.add(new Line(d.id(), d.name(), d.nameZh(), d.priceCents(), e.getValue()));
        }
        if (subtotal < r.minOrderCents()) {
            throw ApiException.badRequest("below_minimum", "Minimum order is " + r.minOrderCents() + " cents");
        }
        return new Priced(r, dishes, lines, subtotal, (int) Math.round(subtotal * r.commissionBps() / 10_000.0), now);
    }

    /** The order transaction. Touches no dish rows, so it never waits on a popular dish. */
    private Outcome create(CurrentUser user, PlaceOrder req, String key, String hash, UUID id, Priced p) {
        Restaurant r = p.restaurant();
        if (!orders.insertIfAbsent(id, user.id(), r.id(), p.subtotal(), r.deliveryFeeCents(), p.commission(),
                req.deliveryAddress().strip(), req.deliveryNotes() == null ? "" : req.deliveryNotes().strip(),
                key, hash, p.now().plus(props.payments().paymentWindow()))) {
            // A concurrent request with the same key won; its row is committed by now. Our holds are released by the caller.
            Outcome replayed = replay(orders.findByKey(user.id(), key).orElseThrow(), hash);
            throw new Replayed(replayed);
        }
        orders.insertItems(id, p.lines());
        orders.insertHistory(id, 1, OrderStatus.PENDING_PAYMENT, user.id(), null);
        db.sql("insert into payments (id, order_id, status, amount_cents) values (?, ?, 'CREATING', ?)")
                .params(UUID.randomUUID(), id, p.subtotal() + r.deliveryFeeCents()).update();
        outbox.publish(Outbox.ORDER_STATUS, id.toString(), new StatusEvent(id, r.id(), user.id(), null,
                OrderStatus.PENDING_PAYMENT, null, 1, p.now(), null));
        stock.confirm(id);
        return new Outcome(id, true);
    }

    /** Signals "same key, already created" out of the transaction so the reserved stock is released. */
    private static final class Replayed extends RuntimeException {
        final Outcome outcome;

        Replayed(Outcome o) {
            super(null, null, false, false);
            this.outcome = o;
        }
    }

    private static String requestHash(long restaurantId, Map<Long, Integer> qty, String address, String notes) {
        String canonical = restaurantId + "|" + qty + "|" + address.strip() + "|" + (notes == null ? "" : notes.strip());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public OrderView get(UUID id, CurrentUser user) {
        Order o = orders.find(id).orElseThrow(() -> ApiException.notFound("Order"));
        authorizeView(o, user);
        return view(o);
    }

    public OrderView act(UUID id, OrderAction action, CurrentUser user, Integer expectedVersion, String reason) {
        tx.run(() -> transitions.apply(new OrderTransitions.Request(id, action, user.id(), expectedVersion,
                o -> authorizeAction(o, action, user), reason)));
        return view(orders.find(id).orElseThrow());
    }

    public java.util.Optional<String> checkout(UUID id, CurrentUser user) {
        Order o = orders.find(id).orElseThrow(() -> ApiException.notFound("Order"));
        if (o.customerId() != user.id()) {
            throw ApiException.notFound("Order");
        }
        if (o.status() != OrderStatus.PENDING_PAYMENT) {
            throw ApiException.conflict("not_payable", "Order is " + o.status());
        }
        return payments.ensureCheckout(id);
    }

    public List<OrderView> mine(CurrentUser user) {
        return orders.forCustomer(user.id(), 30).stream().map(this::view).toList();
    }

    public List<OrderView> forRestaurant(long restaurantId, CurrentUser user) {
        user.requireStaffOf(restaurantId);
        return orders.forRestaurant(restaurantId).stream().map(this::view).toList();
    }

    public List<OrderView> openJobs() {
        return orders.openJobs(50).stream().map(this::view).toList();
    }

    public List<OrderView> riderOrders(CurrentUser user) {
        return orders.forRider(user.id(), 30).stream().map(this::view).toList();
    }

    void authorizeView(Order o, CurrentUser u) {
        boolean ok = switch (u.role()) {
            case ADMIN -> true;
            case CUSTOMER -> o.customerId() == u.id();
            case MERCHANT -> u.restaurantIds().contains(o.restaurantId());
            case RIDER -> (o.riderId() != null && o.riderId() == u.id())
                    || (o.riderId() == null && (o.status() == OrderStatus.ACCEPTED || o.status() == OrderStatus.READY));
        };
        if (!ok) {
            throw ApiException.notFound("Order"); // do not reveal that the id exists
        }
    }

    static void authorizeAction(Order o, OrderAction a, CurrentUser u) {
        boolean ok = switch (a.actor) {
            case CUSTOMER -> u.role() == Role.CUSTOMER && o.customerId() == u.id();
            case MERCHANT -> (u.role() == Role.MERCHANT && u.restaurantIds().contains(o.restaurantId())) || u.role() == Role.ADMIN;
            case RIDER -> u.role() == Role.RIDER && (a == OrderAction.CLAIM || (o.riderId() != null && o.riderId() == u.id()));
            case SYSTEM -> false;
        };
        if (!ok) {
            throw u.role() == Role.CUSTOMER && o.customerId() != u.id() ? ApiException.notFound("Order") : ApiException.forbidden();
        }
    }

    public OrderView view(Order o) {
        Restaurant r = catalog.restaurant(o.restaurantId()).orElseThrow();
        String checkout = o.status() == OrderStatus.PENDING_PAYMENT
                ? db.sql("select checkout_url from payments where order_id = ?").param(o.id()).query(String.class).optional().orElse(null)
                : null;
        return new OrderView(o.id(), o.status(), o.version(),
                new RestaurantRef(r.id(), r.slug(), r.name(), r.nameZh(), r.address()), orders.items(o.id()),
                o.subtotalCents(), o.deliveryFeeCents(), o.totalCents(), o.deliveryAddress(), o.deliveryNotes(),
                o.riderId(), orders.customerName(o.customerId()), o.cancelReason(), o.paymentDeadline(), o.createdAt(),
                o.updatedAt(), checkout, orders.history(o.id()));
    }
}
