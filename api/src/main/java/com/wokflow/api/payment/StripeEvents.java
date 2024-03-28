package com.wokflow.api.payment;

import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Translates Stripe webhook events into the provider-neutral shape PaymentService.handle reads:
 * {"id", "type", "data": {"paymentIntent": <session id>, "refund": <refund id>}}.
 * Events this system has no use for map to empty and are acknowledged without processing.
 */
public final class StripeEvents {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StripeEvents() {}

    public static Optional<byte[]> normalize(JsonNode event) {
        String id = text(event, "id");
        String type = text(event, "type");
        JsonNode obj = event.path("data").path("object");
        if (id == null || type == null || obj.isMissingNode()) {
            return Optional.empty();
        }
        String ourType;
        String intent;
        String refund = null;
        switch (type) {
            case "checkout.session.completed" -> {
                // card payments complete as "paid"; delayed methods complete "unpaid" and settle later
                if (!"paid".equals(text(obj, "payment_status"))) {
                    return Optional.empty();
                }
                ourType = "payment_intent.succeeded";
                intent = text(obj, "id");
            }
            case "checkout.session.async_payment_succeeded" -> {
                ourType = "payment_intent.succeeded";
                intent = text(obj, "id");
            }
            case "checkout.session.async_payment_failed", "checkout.session.expired" -> {
                ourType = "payment_intent.payment_failed";
                intent = text(obj, "id");
            }
            case "refund.created", "refund.updated" -> {
                if (!"succeeded".equals(text(obj, "status"))) {
                    return Optional.empty();
                }
                ourType = "refund.succeeded";
                intent = text(obj.path("metadata"), "session");
                refund = text(obj, "id");
            }
            default -> {
                return Optional.empty();
            }
        }
        if (intent == null) {
            return Optional.empty();
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("id", "stripe:" + id);
        out.put("type", ourType);
        ObjectNode data = out.putObject("data");
        data.put("paymentIntent", intent);
        if (refund != null) {
            data.put("refund", refund);
        }
        return Optional.of(JSON.writeValueAsBytes(out));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
