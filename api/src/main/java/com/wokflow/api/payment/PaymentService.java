package com.wokflow.api.payment;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.Tx;
import com.wokflow.api.config.AppProperties;
import com.wokflow.api.ledger.LedgerService;
import com.wokflow.api.order.OrderAction;
import com.wokflow.api.order.OrderRepository;
import com.wokflow.api.order.OrderStatus;
import com.wokflow.api.order.OrderTransitions;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentProvider provider;
    private final OrderRepository orders;
    private final OrderTransitions transitions;
    private final LedgerService ledger;
    private final JdbcClient db;
    private final Tx tx;
    private final ObjectMapper json;
    private final AppProperties props;
    private final MeterRegistry meters;

    public PaymentService(PaymentProvider provider, OrderRepository orders, OrderTransitions transitions,
                          LedgerService ledger, JdbcClient db, Tx tx, ObjectMapper json, AppProperties props,
                          MeterRegistry meters) {
        this.provider = provider;
        this.orders = orders;
        this.transitions = transitions;
        this.ledger = ledger;
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.props = props;
        this.meters = meters;
    }

    /**
     * Returns the hosted checkout URL, creating the provider intent if needed.
     * Runs outside any database transaction: no row lock is held during the
     * HTTP call. The provider call is idempotent on the order id, so a retry or
     * a concurrent call gets the same intent.
     */
    public Optional<String> ensureCheckout(UUID orderId) {
        record Pay(String intentId, String url, String status, int amount) {}
        Pay p = db.sql("select provider_intent_id, checkout_url, status, amount_cents from payments where order_id = ?")
                .param(orderId).query((rs, n) -> new Pay(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)))
                .optional().orElseThrow(() -> ApiException.notFound("Payment"));
        if (p.url() != null || !"CREATING".equals(p.status())) {
            return Optional.ofNullable(p.url());
        }
        try {
            var intent = provider.createIntent(orderId, p.amount(), "Wokflow order " + orderId.toString().substring(0, 8),
                    props.publicOrigin() + "/orders/" + orderId);
            db.sql("""
                    update payments set provider_intent_id = ?, checkout_url = ?, status = 'REQUIRES_ACTION', updated_at = now()
                    where order_id = ? and status = 'CREATING'""")
                    .params(intent.id(), intent.checkoutUrl(), orderId).update();
            return Optional.of(intent.checkoutUrl());
        } catch (RuntimeException e) {
            meters.counter("wokflow.payments.intent.failures").increment();
            log.warn("Could not create payment intent for {}: {}", orderId, e.toString());
            return Optional.empty();
        }
    }

    public enum WebhookResult { PROCESSED, DUPLICATE, IGNORED }

    /**
     * Handles a verified provider event exactly once. De-duplication, the order
     * transition and the ledger posting share one transaction, so a crash in
     * the middle leaves nothing recorded and the provider's retry starts over.
     */
    public WebhookResult handle(byte[] body) {
        JsonNode event = json.readTree(body);
        String eventId = text(event, "id");
        String type = text(event, "type");
        JsonNode data = event.get("data");
        if (eventId == null || type == null || data == null) {
            throw ApiException.badRequest("bad_event", "Malformed event");
        }
        String intentId = text(data, "paymentIntent");
        WebhookResult result = tx.run(() -> {
            int fresh = db.sql("insert into provider_events (event_id, type) values (?, ?) on conflict do nothing")
                    .params(eventId, type).update();
            if (fresh == 0) {
                return WebhookResult.DUPLICATE;
            }
            UUID orderId = db.sql("select order_id from payments where provider_intent_id = ?").param(intentId)
                    .query(UUID.class).optional().orElse(null);
            if (orderId == null) {
                log.warn("Webhook {} for unknown intent {}", eventId, intentId);
                return WebhookResult.IGNORED;
            }
            switch (type) {
                case "payment_intent.succeeded" -> succeeded(orderId);
                case "payment_intent.payment_failed" -> failed(orderId);
                case "refund.succeeded" -> RefundWorker.markSucceeded(db, ledger, orders, orderId, text(data, "refund"));
                default -> { return WebhookResult.IGNORED; }
            }
            return WebhookResult.PROCESSED;
        });
        meters.counter("wokflow.payments.webhooks", "type", type, "result", result.name()).increment();
        return result;
    }

    private void succeeded(UUID orderId) {
        db.sql("update payments set status = 'SUCCEEDED', updated_at = now() where order_id = ?").param(orderId).update();
        var order = orders.find(orderId).orElseThrow();
        if (order.status() == OrderStatus.PENDING_PAYMENT) {
            try {
                transitions.apply(new OrderTransitions.Request(orderId, OrderAction.PAY, null, null, null, null));
                return;
            } catch (ApiException e) {
                if (e.status() != HttpStatus.CONFLICT) {
                    throw e;
                }
                order = orders.find(orderId).orElseThrow(); // expired or cancelled a moment ago
            }
        }
        if (order.status() == OrderStatus.CANCELLED && ledger.postCapture(order)) {
            // The money arrived after the order expired or was cancelled: book it, then give it back.
            log.info("Late payment for cancelled order {}: refunding", orderId);
            transitions.requestRefund(orderId, order.totalCents(), "payment arrived after cancellation");
        }
    }

    private void failed(UUID orderId) {
        var order = orders.find(orderId).orElseThrow();
        if (order.status() == OrderStatus.PENDING_PAYMENT) {
            try {
                transitions.apply(new OrderTransitions.Request(orderId, OrderAction.DECLINE, null, null, null, "payment_declined"));
            } catch (ApiException e) {
                if (e.status() != HttpStatus.CONFLICT) {
                    throw e;
                }
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
