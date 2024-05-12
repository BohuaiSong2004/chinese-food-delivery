package com.wokflow.api.order;

import static com.wokflow.api.order.OrderStatus.ACCEPTED;
import static com.wokflow.api.order.OrderStatus.CANCELLED;
import static com.wokflow.api.order.OrderStatus.DELIVERED;
import static com.wokflow.api.order.OrderStatus.PAID;
import static com.wokflow.api.order.OrderStatus.PENDING_PAYMENT;
import static com.wokflow.api.order.OrderStatus.PICKED_UP;
import static com.wokflow.api.order.OrderStatus.READY;
import static com.wokflow.api.order.OrderStatus.REJECTED;

import java.util.EnumSet;
import java.util.Set;

/**
 * Every allowed transition, in one place. {@code to == null} means the status
 * does not change (a rider claiming a job only sets rider_id).
 */
public enum OrderAction {
    PAY(EnumSet.of(PENDING_PAYMENT), PAID, Actor.SYSTEM),
    EXPIRE(EnumSet.of(PENDING_PAYMENT), CANCELLED, Actor.SYSTEM),
    DECLINE(EnumSet.of(PENDING_PAYMENT), CANCELLED, Actor.SYSTEM),
    CANCEL(EnumSet.of(PENDING_PAYMENT, PAID), CANCELLED, Actor.CUSTOMER),
    ACCEPT(EnumSet.of(PAID), ACCEPTED, Actor.MERCHANT),
    REJECT(EnumSet.of(PAID), REJECTED, Actor.MERCHANT),
    MARK_READY(EnumSet.of(ACCEPTED), READY, Actor.MERCHANT),
    CLAIM(EnumSet.of(ACCEPTED, READY), null, Actor.RIDER),
    PICK_UP(EnumSet.of(READY), PICKED_UP, Actor.RIDER),
    DELIVER(EnumSet.of(PICKED_UP), DELIVERED, Actor.RIDER);

    public enum Actor { SYSTEM, CUSTOMER, MERCHANT, RIDER }

    public final Set<OrderStatus> from;
    public final OrderStatus to;
    public final Actor actor;

    OrderAction(Set<OrderStatus> from, OrderStatus to, Actor actor) {
        this.from = from;
        this.to = to;
        this.actor = actor;
    }

    public OrderStatus target(OrderStatus current) {
        return to == null ? current : to;
    }
}
