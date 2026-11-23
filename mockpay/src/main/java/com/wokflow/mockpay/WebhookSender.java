package com.wokflow.mockpay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * At-least-once webhook delivery with exponential backoff, like a real provider.
 * Due deliveries are claimed with a short lease (in a short transaction), then
 * sent concurrently; each result is recorded on its own, so one slow receiver
 * response never holds up the rest, and a crash mid-send just lets the lease
 * expire and the delivery is retried.
 */
@Component
class WebhookSender {
    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_IN_FLIGHT = 64;
    private static final Duration LEASE = Duration.ofSeconds(30);

    record Due(long id, String payload, int attempts) {}

    private final JdbcClient db;
    private final MockPayApplication.Props props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore inFlight = new Semaphore(MAX_IN_FLIGHT);

    WebhookSender(JdbcClient db, MockPayApplication.Props props) {
        this.db = db;
        this.props = props;
    }

    @Scheduled(fixedDelay = 50)
    void deliver() {
        int free = inFlight.availablePermits();
        if (free == 0) {
            return;
        }
        // Claim: a single UPDATE ... RETURNING both selects and leases, so concurrent claimers never overlap.
        List<Due> due = db.sql("""
                update webhook_deliveries set next_attempt_at = now() + make_interval(secs => :lease)
                where id in (select id from webhook_deliveries
                             where delivered_at is null and attempts < :max and next_attempt_at <= now()
                             order by id limit :n for update skip locked)
                returning id, payload, attempts""")
                .param("lease", (double) LEASE.toSeconds()).param("max", MAX_ATTEMPTS).param("n", free)
                .query((rs, i) -> new Due(rs.getLong(1), rs.getString(2), rs.getInt(3))).list();
        for (Due d : due) {
            inFlight.acquireUninterruptibly();
            senders.submit(() -> {
                try {
                    record(d, send(d));
                } finally {
                    inFlight.release();
                }
            });
        }
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
            return;
        }
        double delay = Math.min(300, Math.pow(2, d.attempts()));
        log.warn("Webhook {} delivery failed (attempt {}): {}", d.id(), d.attempts() + 1, error);
        db.sql("""
                update webhook_deliveries set attempts = attempts + 1, last_error = ?,
                       next_attempt_at = now() + make_interval(secs => ?) where id = ?""")
                .params(error.length() > 300 ? error.substring(0, 300) : error, delay, d.id()).update();
    }
}
