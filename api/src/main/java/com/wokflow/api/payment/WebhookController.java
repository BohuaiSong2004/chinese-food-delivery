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

    public WebhookController(WebhookVerifier verifier, PaymentService payments) {
        this.verifier = verifier;
        this.payments = payments;
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
