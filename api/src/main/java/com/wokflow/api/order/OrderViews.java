package com.wokflow.api.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class OrderViews {
    private OrderViews() {}

    public record Line(long dishId, String name, String nameZh, int unitPriceCents, int quantity) {}

    public record Step(int version, OrderStatus status, Instant at, String note) {}

    public record RestaurantRef(long id, String slug, String name, String nameZh, String address) {}

    public record OrderView(UUID id, OrderStatus status, int version, RestaurantRef restaurant, List<Line> items,
                            int subtotalCents, int deliveryFeeCents, int totalCents, String deliveryAddress,
                            String deliveryNotes, Long riderId, String customerName, String cancelReason,
                            Instant paymentDeadline, Instant createdAt, Instant updatedAt, String checkoutUrl,
                            List<Step> history) {}

    /** Published on every change; small enough to fan out to many SSE clients. */
    public record StatusEvent(UUID orderId, long restaurantId, long customerId, Long riderId, OrderStatus status,
                              OrderStatus previous, int version, Instant at, String cancelReason) {}
}
