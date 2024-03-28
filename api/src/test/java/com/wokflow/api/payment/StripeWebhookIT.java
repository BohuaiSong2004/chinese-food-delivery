package com.wokflow.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.FakePaymentProvider;
import com.wokflow.api.support.Http;
import com.wokflow.api.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StripeWebhookIT extends IntegrationTest {
    Http.Res stripe(String body, String secret) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return http.raw("POST", "/api/payments/stripe/webhook", bytes, Map.of("Content-Type", "application/json",
                "Stripe-Signature", WebhookVerifier.header(secret, clock.instant().getEpochSecond(), bytes)));
    }

    static String completed(String eventId, String sessionId) {
        return """
                {"id":"%s","object":"event","type":"checkout.session.completed","data":{"object":
                  {"id":"%s","object":"checkout.session","payment_status":"paid","payment_intent":"pi_x"}}}"""
                .formatted(eventId, sessionId);
    }

    @Test
    void aSignedCheckoutCompletionPaysTheOrderOnce() {
        var k = newKitchen(10);
        UUID order = placeOrder(newCustomer(), k.id(), Map.of(k.plainDish(), 2));
        String evt = unique("evt_");

        assertThat(stripe(completed(evt, FakePaymentProvider.intentId(order)), "whsec_wrong").code()).isEqualTo("bad_signature");
        assertThat(status(order)).isEqualTo("PENDING_PAYMENT");

        var first = stripe(completed(evt, FakePaymentProvider.intentId(order)), STRIPE_WEBHOOK_SECRET);
        assertThat(first.body().get("result").asText()).isEqualTo("processed");
        assertThat(status(order)).isEqualTo("PAID");
        assertThat(stripe(completed(evt, FakePaymentProvider.intentId(order)), STRIPE_WEBHOOK_SECRET).body().get("result").asText())
                .isEqualTo("duplicate");
        assertThat(stripe("""
                {"id":"%s","type":"customer.created","data":{"object":{"id":"cus_1"}}}""".formatted(unique("evt_")), STRIPE_WEBHOOK_SECRET)
                .body().get("result").asText()).isEqualTo("ignored");
    }
}
