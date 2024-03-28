package com.wokflow.api.payment;

import com.wokflow.api.config.AppProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** HTTP client for the bundled sandbox provider (mockpay/), shaped like a Stripe-style API. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "wokflow.payments", name = "provider",
        havingValue = "mockpay", matchIfMissing = true)
public class MockPayClient implements PaymentProvider {
    private final RestClient http;

    public MockPayClient(AppProperties props, RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.baseUrl(props.payments().baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.payments().secretKey())
                .build();
    }

    record IntentResponse(String id, String status, String checkoutUrl) {}

    record RefundResponse(String id, String status) {}

    @Override
    public Intent createIntent(UUID orderId, int amountCents, String description, String returnUrl) {
        IntentResponse r = http.post().uri("/v1/payment_intents")
                .header("Idempotency-Key", "order-" + orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amountCents, "currency", "usd", "description", description,
                        "reference", orderId.toString(), "returnUrl", returnUrl))
                .retrieve().body(IntentResponse.class);
        return new Intent(r.id(), r.checkoutUrl(), r.status());
    }

    @Override
    public Refund refund(String intentId, int amountCents, UUID refundId) {
        RefundResponse r = http.post().uri("/v1/refunds")
                .header("Idempotency-Key", "refund-" + refundId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentIntent", intentId, "amount", amountCents))
                .retrieve().body(RefundResponse.class);
        return new Refund(r.id(), r.status());
    }
}
