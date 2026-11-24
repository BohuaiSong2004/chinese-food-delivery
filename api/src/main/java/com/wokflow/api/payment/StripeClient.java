package com.wokflow.api.payment;

import com.wokflow.api.config.AppProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/**
 * Stripe adapter (PAYMENT_PROVIDER=stripe): one hosted Checkout Session per order, refunds against
 * its PaymentIntent. The session id is what the rest of the system stores as the "intent id", so
 * webhooks and refunds find the order the same way as with MockPay. Requests are form-encoded and
 * idempotent on the same keys; the request shapes are checked against stripe-mock (StripeClientIT),
 * which validates every call against Stripe's published OpenAPI spec.
 */
@Component
@ConditionalOnProperty(prefix = "wokflow.payments", name = "provider", havingValue = "stripe")
public class StripeClient implements PaymentProvider {
    private final RestClient http;

    public StripeClient(AppProperties props, RestClient.Builder builder) {
        AppProperties.Stripe s = props.payments().stripe();
        if (s.secretKey() == null || !s.secretKey().startsWith("sk_test_")) {
            // a portfolio project: refuse to run against a live account by accident
            throw new IllegalStateException("STRIPE_SECRET_KEY must be a test-mode key (sk_test_...)");
        }
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = builder.baseUrl(s.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + s.secretKey())
                .build();
    }

    static MultiValueMap<String, String> sessionForm(UUID orderId, int amountCents, String description, String returnUrl) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("mode", "payment");
        f.add("success_url", returnUrl);
        f.add("cancel_url", returnUrl);
        f.add("client_reference_id", orderId.toString());
        f.add("line_items[0][quantity]", "1");
        f.add("line_items[0][price_data][currency]", "usd");
        f.add("line_items[0][price_data][unit_amount]", Integer.toString(amountCents));
        f.add("line_items[0][price_data][product_data][name]", description);
        f.add("metadata[order_id]", orderId.toString());
        f.add("payment_intent_data[metadata][order_id]", orderId.toString());
        return f;
    }

    @Override
    public Intent createIntent(UUID orderId, int amountCents, String description, String returnUrl) {
        JsonNode s = http.post().uri("/v1/checkout/sessions")
                .header("Idempotency-Key", "order-" + orderId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(sessionForm(orderId, amountCents, description, returnUrl))
                .retrieve().body(JsonNode.class);
        return new Intent(s.get("id").asText(), s.get("url").asText(), "requires_action");
    }

    /** Looks up the session's PaymentIntent (set once the customer has paid) and refunds it. */
    @Override
    public Refund refund(String sessionId, int amountCents, UUID refundId) {
        JsonNode session = http.get().uri("/v1/checkout/sessions/{id}", sessionId).retrieve().body(JsonNode.class);
        JsonNode pi = session.get("payment_intent");
        if (pi == null || pi.isNull()) {
            throw new IllegalStateException("Checkout Session " + sessionId + " has no PaymentIntent yet");
        }
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("payment_intent", pi.isObject() ? pi.get("id").asText() : pi.asText());
        f.add("amount", Integer.toString(amountCents));
        f.add("metadata[session]", sessionId);
        f.add("metadata[refund_id]", refundId.toString());
        JsonNode r = http.post().uri("/v1/refunds")
                .header("Idempotency-Key", "refund-" + refundId)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(f)
                .retrieve().body(JsonNode.class);
        return new Refund(r.get("id").asText(), r.get("status").asText());
    }
}
