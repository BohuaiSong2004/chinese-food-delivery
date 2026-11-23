package com.wokflow.mockpay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** At-least-once webhook delivery with exponential backoff, like a real provider. */
@Component
class WebhookSender {
    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final int MAX_ATTEMPTS = 20;

    record Due(long id, String payload, int attempts) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final MockPayApplication.Props props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    WebhookSender(JdbcClient db, TransactionTemplate tx, MockPayApplication.Props props) {
        this.db = db;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelay = 100)
    void deliver() {
        Integer n;
        do {
            n = tx.execute(s -> {
                List<Due> due = db.sql("""
                        select id, payload, attempts from webhook_deliveries
                        where delivered_at is null and attempts < ? and next_attempt_at <= now()
                        order by id limit 50 for update skip locked""")
                        .param(MAX_ATTEMPTS)
                        .query((rs, i) -> new Due(rs.getLong(1), rs.getString(2), rs.getInt(3))).list();
                // HTTP calls in parallel; the row updates stay on this thread, inside the transaction
                // that holds the row locks (updating them from other threads would wait on ourselves).
                record Result(Due due, String error) {}
                var results = due.parallelStream().map(d -> new Result(d, send(d))).toList();
                results.forEach(r -> record(r.due(), r.error()));
                return due.size();
            });
        } while (n != null && n == 50);
    }

    /** Returns null on success, otherwise the error. */
    private String send(Due d) {
        long t = Instant.now().getEpochSecond();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.webhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("MockPay-Signature", Signatures.header(props.webhookSecret(), t, d.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(d.payload())).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() / 100 == 2 ? null : "HTTP " + res.statusCode();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return e.toString();
        }
    }

    private void record(Due d, String error) {
        if (error == null) {
            db.sql("update webhook_deliveries set delivered_at = now(), attempts = attempts + 1 where id = ?").param(d.id()).update();
        } else {
            fail(d, error);
        }
    }

    private void fail(Due d, String error) {
        double delay = Math.min(300, Math.pow(2, d.attempts()));
        log.warn("Webhook {} delivery failed (attempt {}): {}", d.id(), d.attempts() + 1, error);
        db.sql("""
                update webhook_deliveries set attempts = attempts + 1, last_error = ?,
                       next_attempt_at = now() + make_interval(secs => ?) where id = ?""")
                .params(error.length() > 300 ? error.substring(0, 300) : error, delay, d.id()).update();
    }
}
