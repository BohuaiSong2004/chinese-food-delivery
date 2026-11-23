package com.wokflow.api.payment;

import java.util.UUID;

/** The external payment processor. Every call is idempotent on the key we pass. */
public interface PaymentProvider {
    record Intent(String id, String checkoutUrl, String status) {}

    record Refund(String id, String status) {}

    Intent createIntent(UUID orderId, int amountCents, String description, String returnUrl);

    Refund refund(String intentId, int amountCents, UUID refundId);
}
