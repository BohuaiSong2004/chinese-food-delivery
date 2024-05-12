package com.wokflow.api.order;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.order.OrderRepository.Changed;
import com.wokflow.api.order.OrderRepository.Order;
import com.wokflow.api.order.OrderViews.StatusEvent;
import com.wokflow.api.outbox.Outbox;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one {@link OrderAction} and its side effects atomically: the status
 * change, the history row, stock release, ledger posting, refund request and
 * the outbox events all commit together or not at all.
 */
@Component
public class OrderTransitions {
    public record Request(UUID orderId, OrderAction action, Long actorId, Integer expectedVersion,
                          Consumer<Order> authorize, String reason) {}

    private final OrderRepository orders;
    private final LedgerService ledger;
    private final Outbox outbox;
    private final JdbcClient db;

    public OrderTransitions(OrderRepository orders, LedgerService ledger, Outbox outbox, JdbcClient db) {
        this.orders = orders;
        this.ledger = ledger;
        this.outbox = outbox;
        this.db = db;
    }

    /**
     * Reads the order, checks it, then compare-and-sets on the version it read,
     * so the side effects are chosen for the exact status that was replaced. If
     * someone else changed the order in between, it re-reads and decides again
     * (unless the caller pinned a version, in which case that is a conflict).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Changed apply(Request r) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Order o = orders.find(r.orderId()).orElseThrow(() -> ApiException.notFound("Order"));
            if (r.authorize() != null) {
                r.authorize().accept(o);
            }
            if (r.expectedVersion() != null && r.expectedVersion() != o.version()) {
                if (alreadyDoneByCaller(r, o)) {
                    return new Changed(o.version(), o.status(), o.customerId(), o.restaurantId(), o.riderId(), o.updatedAt());
                }
                throw stale(o);
            }
            if (!r.action().from.contains(o.status())) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "invalid_transition",
                        "Order is " + o.status() + "; cannot " + r.action().name().toLowerCase().replace('_', ' '));
            }
            boolean claim = r.action() == OrderAction.CLAIM;
            if (claim && o.riderId() != null) {
                throw ApiException.conflict("already_claimed", "Another rider took this delivery");
            }
            boolean riderStep = r.action() == OrderAction.PICK_UP || r.action() == OrderAction.DELIVER;
            var changed = orders.compareAndSet(o.id(), List.of(o.status()), r.action().to, o.version(),
                    riderStep ? r.actorId() : null, claim, claim ? r.actorId() : null, r.reason());
            if (changed.isPresent()) {
                effects(o, r, changed.get());
                return changed.get();
            }
            if (r.expectedVersion() != null) {
                throw stale(orders.find(r.orderId()).orElse(o));
            }
        }
        throw ApiException.conflict("busy", "Order is changing quickly, try again");
    }

    private void effects(Order before, Request r, Changed after) {
        orders.insertHistory(before.id(), after.version(), after.status(), r.actorId(), note(r));
        outbox.publish(Outbox.ORDER_STATUS, before.id().toString(), new StatusEvent(before.id(), after.restaurantId(),
                after.customerId(), after.riderId(), after.status(), before.status(), after.version(), after.updatedAt(),
                r.reason()));
        switch (r.action()) {
            case PAY -> {
                ledger.postCapture(before);
                List<Map<String, Object>> items = orders.items(before.id()).stream()
                        .map(l -> Map.<String, Object>of("dishId", l.dishId(), "quantity", l.quantity())).toList();
                outbox.publish(Outbox.ORDER_PAID, before.id().toString(), Map.of("orderId", before.id(),
                        "restaurantId", before.restaurantId(), "items", items, "paidAt", after.updatedAt()));
            }
            case EXPIRE, DECLINE -> {
                releaseStock(before.id());
                db.sql("update payments set status = ?, updated_at = now() where order_id = ? and status in ('CREATING', 'REQUIRES_ACTION')")
                        .params(r.action() == OrderAction.DECLINE ? "FAILED" : "CANCELLED", before.id()).update();
            }
            case CANCEL, REJECT -> {
                releaseStock(before.id());
                if (before.status() == OrderStatus.PAID) {
                    requestRefund(before.id(), before.totalCents(), r.action() == OrderAction.REJECT ? "rejected by restaurant" : "cancelled by customer");
                } else {
                    db.sql("update payments set status = 'CANCELLED', updated_at = now() where order_id = ? and status in ('CREATING', 'REQUIRES_ACTION')")
                            .param(before.id()).update();
                }
            }
            default -> { }
        }
    }

    /** Returns stock in ascending dish id order, the same order placement locks in, so the two cannot deadlock. */
    void releaseStock(UUID orderId) {
        orders.items(orderId).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderViews.Line::dishId,
                        java.util.TreeMap::new, java.util.stream.Collectors.summingInt(OrderViews.Line::quantity)))
                .forEach(orders::releaseStock);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRefund(UUID orderId, int amountCents, String reason) {
        db.sql("""
                insert into refunds (id, order_id, payment_id, amount_cents, reason, status)
                select ?, ?, p.id, ?, ?, 'REQUESTED' from payments p where p.order_id = ?
                on conflict (order_id) do nothing""")
                .params(UUID.randomUUID(), orderId, amountCents, reason, orderId).update();
    }

    /**
     * A retry of a change that already happened. The client sent the version it saw; if the very
     * next version is this same action by this same actor, the first attempt committed and only its
     * response was lost (a timeout, or a 503 from a proxy), so the retry succeeds with the current
     * state instead of a confusing "stale version" conflict.
     */
    private boolean alreadyDoneByCaller(Request r, Order current) {
        if (r.actorId() == null || current.version() <= r.expectedVersion()) {
            return false;
        }
        return orders.historyAt(r.orderId(), r.expectedVersion() + 1)
                .filter(h -> r.actorId().equals(h.actorId()))
                .filter(h -> r.action().to == null || h.status() == r.action().to)
                .filter(h -> java.util.Objects.equals(h.note(), note(r)))
                .isPresent();
    }

    private static String note(Request r) {
        return switch (r.action()) {
            case EXPIRE -> "payment window expired";
            case DECLINE -> "payment declined";
            case CLAIM -> "rider assigned";
            default -> r.reason();
        };
    }

    private static ApiException stale(Order current) {
        return new ApiException(org.springframework.http.HttpStatus.CONFLICT, "stale_version",
                "Order changed to " + current.status() + " (version " + current.version() + "); refresh and try again");
    }
}
