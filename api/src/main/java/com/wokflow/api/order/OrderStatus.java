package com.wokflow.api.order;

import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, ACCEPTED, READY, PICKED_UP, DELIVERED, CANCELLED, REJECTED;

    public boolean terminal() {
        return this == DELIVERED || this == CANCELLED || this == REJECTED;
    }

    /** Statuses in which the customer has been charged and the money has not gone back. */
    public static final Set<OrderStatus> CHARGED = EnumSet.of(PAID, ACCEPTED, READY, PICKED_UP, DELIVERED);
}
