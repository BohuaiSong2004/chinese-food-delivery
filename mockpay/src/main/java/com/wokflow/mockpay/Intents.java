package com.wokflow.mockpay;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
class Intents {
    record Intent(String id, int amount, String currency, String description, String reference, String returnUrl,
                  String status, int refunded, Instant createdAt) {}

    record Refund(String id, String intentId, int amount, String status) {}

    private final JdbcClient db;
    private final ObjectMapper json;
    private final MockPayApplication.Props props;
    private final SecureRandom random = new SecureRandom();

    Intents(JdbcClient db, ObjectMapper json, MockPayApplication.Props props) {
        this.db = db;
        this.json = json;
        this.props = props;
    }

    String newId(String prefix) {
        byte[] b = new byte[12];
        random.nextBytes(b);
        return prefix + "_" + HexFormat.of().formatHex(b);
    }

    @Transactional
    Intent create(String key, int amount, String currency, String description, String reference, String returnUrl) {
        if (props.allowedReturnOrigins().stream().noneMatch(o -> returnUrl.startsWith(o + "/"))) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "returnUrl must be on an allowed origin");
        }
        db.sql("""
                insert into intents (id, idempotency_key, amount, currency, description, reference, return_url, status)
                values (?, ?, ?, ?, ?, ?, ?, 'requires_action') on conflict (idempotency_key) do nothing""")
                .params(newId("pi"), key, amount, currency, description, reference, returnUrl).update();
        Intent i = db.sql("select * from intents where idempotency_key = ?").param(key).query(Intents::map).single();
        if (i.amount() != amount || !i.reference().equals(reference)) {
            throw new ApiError(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key reused with different parameters");
        }
        return i;
    }

    Optional<Intent> find(String id) {
        return db.sql("select * from intents where id = ?").param(id).query(Intents::map).optional();
    }

    /** The customer's decision on the hosted page. Only the first one counts. */
    @Transactional
    Intent confirm(String id, boolean succeed) {
        int n = db.sql("update intents set status = ?, updated_at = now() where id = ? and status = 'requires_action'")
                .params(succeed ? "succeeded" : "failed", id).update();
        Intent i = find(id).orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such payment intent"));
        if (n == 1) {
            enqueue(succeed ? "payment_intent.succeeded" : "payment_intent.payment_failed",
                    Map.of("paymentIntent", i.id(), "amount", i.amount(), "reference", i.reference()));
        }
        return i;
    }

    @Transactional
    Refund refund(String key, String intentId, int amount) {
        var existing = db.sql("select id, intent_id, amount, status from refunds where idempotency_key = ?").param(key)
                .query((rs, n) -> new Refund(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4))).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        // Lock the intent so concurrent refunds cannot exceed the captured amount.
        Intent i = db.sql("select * from intents where id = ? for update").param(intentId).query(Intents::map).optional()
                .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "No such payment intent"));
        if (!"succeeded".equals(i.status())) {
            throw new ApiError(HttpStatus.CONFLICT, "Only succeeded payments can be refunded");
        }
        if (i.refunded() + amount > i.amount()) {
            throw new ApiError(HttpStatus.CONFLICT, "Refund exceeds the remaining captured amount");
        }
        String id = newId("re");
        db.sql("insert into refunds (id, idempotency_key, intent_id, amount, status) values (?, ?, ?, ?, 'succeeded')")
                .params(id, key, intentId, amount).update();
        db.sql("update intents set refunded = refunded + ?, updated_at = now() where id = ?").params(amount, intentId).update();
        enqueue("refund.succeeded", Map.of("paymentIntent", intentId, "refund", id, "amount", amount));
        return new Refund(id, intentId, amount, "succeeded");
    }

    private void enqueue(String type, Map<String, Object> data) {
        String eventId = newId("evt");
        String payload = json.writeValueAsString(Map.of("id", eventId, "type", type,
                "created", Instant.now().getEpochSecond(), "data", data));
        int copies = Math.random() < props.duplicateRate() ? 2 : 1;
        for (int c = 0; c < copies; c++) {
            db.sql("insert into webhook_deliveries (event_id, type, payload) values (?, ?, ?)")
                    .params(eventId, type, payload).update();
        }
    }

    static Intent map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Intent(rs.getString("id"), rs.getInt("amount"), rs.getString("currency"), rs.getString("description"),
                rs.getString("reference"), rs.getString("return_url"), rs.getString("status"), rs.getInt("refunded"),
                rs.getTimestamp("created_at").toInstant());
    }
}
