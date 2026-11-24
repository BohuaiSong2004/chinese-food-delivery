package com.wokflow.api.payment;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.config.AppProperties;
import java.time.Clock;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** Stripe's callbacks, verified with STRIPE_WEBHOOK_SECRET; 404 when Stripe is not configured. */
@RestController
public class StripeWebhookController {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WebhookVerifier verifier;
    private final PaymentService payments;

    public StripeWebhookController(AppProperties props, PaymentService payments, Clock clock) {
        AppProperties.Stripe s = props.payments().stripe();
        this.verifier = s == null || s.webhookSecret() == null || s.webhookSecret().isBlank()
                ? null : new WebhookVerifier(s.webhookSecret(), props.payments().webhookTolerance(), clock);
        this.payments = payments;
    }

    @PostMapping("/api/payments/stripe/webhook")
    Map<String, String> webhook(@RequestBody byte[] body,
                                @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        if (verifier == null) {
            throw ApiException.notFound("Stripe webhook");
        }
        if (body.length > 256 * 1024) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Event too large");
        }
        if (!verifier.verify(body, signature)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_signature", "Signature verification failed");
        }
        return StripeEvents.normalize(JSON.readTree(body))
                .map(e -> Map.of("result", payments.handle(e).name().toLowerCase()))
                .orElse(Map.of("result", "ignored"));
    }
}
