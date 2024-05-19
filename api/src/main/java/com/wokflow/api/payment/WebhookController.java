package com.wokflow.api.payment;

import com.wokflow.api.common.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Provider callbacks. Authenticated by the HMAC signature, not by a user token. */
@RestController
public class WebhookController {
    private final WebhookVerifier verifier;
    private final PaymentService payments;
    private final org.springframework.jdbc.core.simple.JdbcClient db;

    public WebhookController(WebhookVerifier verifier, PaymentService payments, org.springframework.jdbc.core.simple.JdbcClient db) {
        this.verifier = verifier;
        this.payments = payments;
        this.db = db;
    }

    /** Providers retry for days, not months: de-duplication records older than 30 days can go. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public int purgeOldEvents() {
        return db.sql("delete from provider_events where received_at < now() - interval '30 days'").update();
    }

    @PostMapping("/api/payments/webhook")
    Map<String, String> webhook(@RequestBody byte[] body,
                                @RequestHeader(name = "MockPay-Signature", required = false) String signature) {
        if (body.length > 64 * 1024) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Event too large");
        }
        if (!verifier.verify(body, signature)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_signature", "Signature verification failed");
        }
        return Map.of("result", payments.handle(body).name().toLowerCase());
    }
}
