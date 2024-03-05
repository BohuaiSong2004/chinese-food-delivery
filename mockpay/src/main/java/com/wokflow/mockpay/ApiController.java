package com.wokflow.mockpay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Server-to-server API, authenticated with the secret key. */
@RestController
class ApiController {
    record CreateIntent(@Positive @Max(10_000_000) int amount, @NotBlank @Size(max = 3) String currency,
                        @NotBlank @Size(max = 200) String description, @NotBlank @Size(max = 100) String reference,
                        @NotBlank @Size(max = 500) String returnUrl) {}

    record CreateRefund(@NotBlank String paymentIntent, @Positive int amount) {}

    record Confirm(@NotBlank String outcome) {}

    private final Intents intents;
    private final MockPayApplication.Props props;

    ApiController(Intents intents, MockPayApplication.Props props) {
        this.intents = intents;
        this.props = props;
    }

    @PostMapping("/v1/payment_intents")
    Map<String, Object> create(@RequestHeader(name = "Authorization", required = false) String auth,
                               @RequestHeader(name = "Idempotency-Key", required = false) String key,
                               @Valid @RequestBody CreateIntent req) {
        authenticate(auth);
        requireKey(key);
        return view(intents.create(key, req.amount(), req.currency(), req.description(), req.reference(), req.returnUrl()));
    }

    @GetMapping("/v1/payment_intents/{id}")
    Map<String, Object> get(@RequestHeader(name = "Authorization", required = false) String auth, @PathVariable String id) {
        authenticate(auth);
        return view(intents.find(id).orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such payment intent")));
    }

    /** Test-mode shortcut (like confirming with a test card): used by load tests instead of clicking the hosted page. */
    @PostMapping("/v1/payment_intents/{id}/confirm")
    Map<String, Object> confirm(@RequestHeader(name = "Authorization", required = false) String auth,
                                @PathVariable String id, @Valid @RequestBody Confirm req) {
        authenticate(auth);
        return view(intents.confirm(id, !"decline".equals(req.outcome())));
    }

    @PostMapping("/v1/refunds")
    Map<String, Object> refund(@RequestHeader(name = "Authorization", required = false) String auth,
                               @RequestHeader(name = "Idempotency-Key", required = false) String key,
                               @Valid @RequestBody CreateRefund req) {
        authenticate(auth);
        requireKey(key);
        var r = intents.refund(key, req.paymentIntent(), req.amount());
        return Map.of("id", r.id(), "paymentIntent", r.intentId(), "amount", r.amount(), "status", r.status());
    }

    private Map<String, Object> view(Intents.Intent i) {
        return Map.of("id", i.id(), "amount", i.amount(), "currency", i.currency(), "status", i.status(),
                "reference", i.reference(), "refunded", i.refunded(),
                "checkoutUrl", props.publicUrl() + "/checkout/" + i.id());
    }

    private void authenticate(String header) {
        byte[] expected = ("Bearer " + props.secretKey()).getBytes(StandardCharsets.UTF_8);
        if (header == null || !MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "Invalid API key");
        }
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || key.length() > 100) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "Idempotency-Key header is required");
        }
    }

    @ExceptionHandler(ApiError.class)
    ResponseEntity<Map<String, String>> error(ApiError e) {
        return ResponseEntity.status(e.status).body(Map.of("error", e.getMessage()));
    }
}
