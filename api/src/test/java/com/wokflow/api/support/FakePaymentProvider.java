package com.wokflow.api.support;

import com.wokflow.api.payment.PaymentProvider;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory provider: tests drive payment outcomes by posting signed webhooks themselves. */
public class FakePaymentProvider implements PaymentProvider {
    public final AtomicBoolean failIntents = new AtomicBoolean();
    public final AtomicBoolean failRefunds = new AtomicBoolean();
    public final List<String> refunds = new CopyOnWriteArrayList<>();

    public static String intentId(UUID orderId) { return "pi_test_" + orderId.toString().replace("-", ""); }

    @Override
    public Intent createIntent(UUID orderId, int amountCents, String description, String returnUrl) {
        if (failIntents.get()) {
            throw new IllegalStateException("provider down (test)");
        }
        String id = intentId(orderId);
        return new Intent(id, "https://pay.test/checkout/" + id, "requires_action");
    }

    @Override
    public Refund refund(String intentId, int amountCents, UUID refundId) {
        if (failRefunds.get()) {
            throw new IllegalStateException("provider down (test)");
        }
        refunds.add(intentId);
        return new Refund("re_test_" + refundId.toString().replace("-", ""), "succeeded");
    }
}
