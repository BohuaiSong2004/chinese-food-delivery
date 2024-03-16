package com.wokflow.api.outbox;

import com.wokflow.api.config.AppProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers outbox rows to handlers. Every replica runs one publisher; rows are
 * claimed with FOR UPDATE SKIP LOCKED, so replicas share the work without
 * blocking each other. A trigger NOTIFYs on insert, so delivery normally starts
 * within milliseconds; a 1 s poll covers missed notifications and retries.
 */
@Component
public class OutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 200;

    record Row(long id, String topic, String aggregateId, String payload, int attempts) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Map<String, List<OutboxHandler>> handlers = new HashMap<>();
    private final DataSourceProperties dataSource;
    private final AppProperties props;
    private final Clock clock;
    private final Counter published;
    private final Counter failures;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Object wake = new Object();
    private volatile boolean pending = true;
    private Thread listener;
    private Thread worker;

    public OutboxPublisher(JdbcClient db, TransactionTemplate tx, ObjectMapper json, List<OutboxHandler> all,
                           DataSourceProperties dataSource, AppProperties props, Clock clock, MeterRegistry meters) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.dataSource = dataSource;
        this.props = props;
        this.clock = clock;
        for (OutboxHandler h : all) {
            for (String t : h.topics()) {
                handlers.computeIfAbsent(t, k -> new ArrayList<>()).add(h);
            }
        }
        this.published = meters.counter("wokflow.outbox.published");
        this.failures = meters.counter("wokflow.outbox.failures");
        Gauge.builder("wokflow.outbox.lag.seconds", this, OutboxPublisher::lagSeconds).register(meters);
    }

    @Override
    public void start() {
        running.set(true);
        worker = Thread.ofVirtual().name("outbox-worker").start(this::workLoop);
        listener = Thread.ofVirtual().name("outbox-listen").start(this::listenLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        if (listener != null) listener.interrupt();
        if (worker != null) worker.interrupt();
    }

    @Override
    public boolean isRunning() { return running.get(); }

    @Override
    public int getPhase() { return Integer.MAX_VALUE - 100; } // start after the web server and data sources

    private void signal() {
        synchronized (wake) {
            pending = true;
            wake.notifyAll();
        }
    }

    private void listenLoop() {
        while (running.get()) {
            try (Connection c = DriverManager.getConnection(dataSource.determineUrl(),
                    dataSource.determineUsername(), dataSource.determinePassword())) {
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN outbox");
                }
                PGConnection pg = c.unwrap(PGConnection.class);
                signal(); // catch up on anything written while we were not listening
                while (running.get()) {
                    var notes = pg.getNotifications(1000);
                    if (notes != null && notes.length > 0) {
                        signal();
                    }
                }
            } catch (Exception e) {
                if (!running.get()) {
                    return;
                }
                log.warn("Outbox LISTEN connection lost, retrying: {}", e.toString());
                sleep(Duration.ofSeconds(1));
            }
        }
    }

    private void workLoop() {
        while (running.get()) {
            try {
                synchronized (wake) {
                    if (!pending) {
                        wake.wait(1000);
                    }
                    pending = false;
                }
                while (running.get() && drainOnce() == BATCH) {
                    // keep going while full batches come back
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                if (running.get()) {
                    log.warn("Outbox drain failed: {}", e.toString());
                    sleep(Duration.ofMillis(500));
                }
            }
        }
    }

    /** Claims and delivers one batch; returns how many rows it claimed. Package-private for tests. */
    int drainOnce() {
        Integer n = tx.execute(status -> {
            List<Row> rows = db.sql("""
                    select id, topic, aggregate_id, payload::text, attempts from outbox
                    where published_at is null and next_attempt_at <= now()
                    order by id limit ? for update skip locked""")
                    .param(BATCH)
                    .query((rs, i) -> new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
                    .list();
            List<Long> done = new ArrayList<>();
            for (Row r : rows) {
                try {
                    JsonNode payload = json.readTree(r.payload());
                    for (OutboxHandler h : handlers.getOrDefault(r.topic(), List.of())) {
                        h.handle(r.topic(), r.aggregateId(), payload);
                    }
                    done.add(r.id());
                } catch (Exception e) {
                    failures.increment();
                    long backoff = Math.min(60_000L, 250L << Math.min(r.attempts(), 8));
                    log.warn("Outbox event {} ({}) failed, attempt {}: {}", r.id(), r.topic(), r.attempts() + 1, e.toString());
                    db.sql("""
                            update outbox set attempts = attempts + 1, last_error = ?,
                                   next_attempt_at = now() + make_interval(secs => ?) where id = ?""")
                            .params(truncate(e.toString()), backoff / 1000.0, r.id()).update();
                }
            }
            if (!done.isEmpty()) {
                db.sql("update outbox set published_at = now() where id = any(?)").param(done.toArray(Long[]::new)).update();
                published.increment(done.size());
            }
            return rows.size();
        });
        return n == null ? 0 : n;
    }

    /** Waits until every event written so far has been delivered (used by tests and the load-test verifier). */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Long left = db.sql("select count(*) from outbox where published_at is null").query(Long.class).single();
            if (left == 0) {
                return true;
            }
            signal();
            sleep(Duration.ofMillis(50));
        }
        return false;
    }

    double lagSeconds() {
        try {
            return db.sql("select coalesce(extract(epoch from now() - min(created_at)), 0) from outbox where published_at is null")
                    .query(Double.class).single();
        } catch (Exception e) {
            return -1;
        }
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void purge() {
        int n = db.sql("delete from outbox where published_at < ?")
                .param(java.sql.Timestamp.from(clock.instant().minus(props.outbox().retention()))).update();
        if (n > 0) {
            log.info("Purged {} delivered outbox rows", n);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() { signal(); }

    private static String truncate(String s) { return s.length() > 500 ? s.substring(0, 500) : s; }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
