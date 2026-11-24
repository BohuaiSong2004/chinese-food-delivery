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
 * blocking each other. A committed write wakes this replica's publisher at once
 * (OutboxWakeup) and peers through a coalesced NOTIFY, so delivery normally starts
 * within milliseconds; a 1 s poll covers missed notifications and retries.
 */
@Component
public class OutboxPublisher implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH = 200;

    record Row(long id, String topic, String aggregateId, String payload, int attempts) {}

    private final com.wokflow.api.common.RedisBreaker redisBreaker;

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
                           DataSourceProperties dataSource, AppProperties props, Clock clock, MeterRegistry meters,
                           com.wokflow.api.common.RedisBreaker redisBreaker, OutboxWakeup wakeup) {
        this.redisBreaker = redisBreaker;
        wakeup.onWake(this::signal);
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
        this.meters = meters;
        this.published = meters.counter("wokflow.outbox.published");
        this.failures = meters.counter("wokflow.outbox.failures");
        Gauge.builder("wokflow.outbox.lag.seconds", this, OutboxPublisher::lagSeconds).register(meters);
    }

    @Override
    public void start() {
        running.set(true);
        // Platform threads: both loops block for long periods (Object.wait, a JDBC socket read), which on
        // Java 21 pins a virtual thread's carrier, and a 2-core box only has two carriers.
        worker = Thread.ofPlatform().daemon().name("outbox-worker").start(this::workLoop);
        listener = Thread.ofPlatform().daemon().name("outbox-listen").start(this::listenLoop);
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
                // Keep going while a pass did any work: publishing an event can unblock the next event of the
                // same aggregate (set aside because it waited behind it), and nothing else would wake us for that.
                while (running.get() && drainOnce() > 0) {
                    // next pass
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
            Map<String, Long> cutoffs = outsideCutoffs(rows);
            int processed = 0;
            List<Long> done = new ArrayList<>();
            Map<OutboxHandler, List<Row>> batched = new java.util.LinkedHashMap<>();
            Map<OutboxHandler, List<OutboxHandler.Event>> batchedEvents = new java.util.LinkedHashMap<>();
            // A topic whose handler just failed (its store is down or hanging) is not tried again in this
            // batch: without this, one frozen dependency made every queued event wait out its own timeout,
            // and events for healthy stores waited behind them.
            Map<String, String> failedTopics = new HashMap<>();
            for (Row r : rows) {
                Long cutoff = cutoffs.get(r.topic() + "/" + r.aggregateId());
                if (cutoff != null && r.id() > cutoff) {
                    continue; // left untouched; claimable again once the earlier event is out
                }
                processed++;
                String earlier = failedTopics.get(r.topic());
                if (earlier != null) {
                    hold(r, "skipped: " + earlier);
                    continue;
                }
                List<OutboxHandler> hs = handlers.getOrDefault(r.topic(), List.of());
                boolean redis = hs.stream().anyMatch(OutboxHandler::usesRedis);
                if (redis && redisBreaker.isOpen()) {
                    hold(r, "held: Redis unavailable");
                    continue;
                }
                try {
                    JsonNode payload = json.readTree(r.payload());
                    for (OutboxHandler h : hs) {
                        if (h.batches()) {
                            continue;
                        }
                        long t0 = System.nanoTime();
                        h.handle(r.topic(), r.aggregateId(), payload);
                        handleTimer(h).record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
                    }
                    for (OutboxHandler h : hs) {
                        if (h.batches()) {
                            batched.computeIfAbsent(h, k -> new ArrayList<>()).add(r);
                            batchedEvents.computeIfAbsent(h, k -> new ArrayList<>())
                                    .add(new OutboxHandler.Event(r.topic(), r.aggregateId(), payload));
                        }
                    }
                    done.add(r.id());
                } catch (Exception e) {
                    failures.increment();
                    log.warn("Outbox event {} ({}) failed, attempt {}: {}", r.id(), r.topic(), r.attempts() + 1, e.toString());
                    failedTopics.put(r.topic(), e.toString());
                    if (redis && com.wokflow.api.common.RedisBreaker.isOutage(e)) {
                        redisBreaker.trip("outbox " + r.topic(), e);
                    }
                    defer(r, e.toString());
                }
            }
            // Batching handlers (the live stream) send everything collected above in one go, in id order.
            // If that fails, those rows are not published: they are deferred and retried, like a failure above.
            for (var entry : batchedEvents.entrySet()) {
                OutboxHandler h = entry.getKey();
                long t0 = System.nanoTime();
                try {
                    h.handleBatch(entry.getValue());
                    handleTimer(h).record(System.nanoTime() - t0, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (Exception e) {
                    failures.increment();
                    log.warn("Outbox batch of {} events for {} failed: {}", entry.getValue().size(), h.getClass().getSimpleName(), e.toString());
                    if (h.usesRedis() && com.wokflow.api.common.RedisBreaker.isOutage(e)) {
                        redisBreaker.trip("outbox batch", e);
                    }
                    for (Row r : batched.get(h)) {
                        if (done.remove(r.id())) {
                            defer(r, e.toString());
                        }
                    }
                }
            }
            if (!done.isEmpty()) {
                db.sql("update outbox set published_at = now() where id = any(?)").param(done.toArray(Long[]::new)).update();
                published.increment(done.size());
            }
            return processed;
        });
        return n == null ? 0 : n;
    }

    /**
     * Per-aggregate order. Events of one order (or dish) in this batch are handled in id order, but
     * only up to the first pending event of that aggregate that is not in this batch: one another
     * replica is publishing (or has set aside) right now, or one waiting to be retried. Everything
     * after it waits. Without this, two replicas publishing their own commits at the same moment put
     * an order's READY before its ACCEPTED in the live stream.
     *
     * @return for each aggregate in the batch, the id of the first pending event outside the batch
     */
    private Map<String, Long> outsideCutoffs(List<Row> rows) {
        Map<String, Row> first = new java.util.LinkedHashMap<>();
        for (Row r : rows) {
            first.putIfAbsent(r.topic() + "/" + r.aggregateId(), r);
        }
        if (first.isEmpty()) {
            return Map.of();
        }
        String[] topics = first.values().stream().map(Row::topic).toArray(String[]::new);
        String[] aggs = first.values().stream().map(Row::aggregateId).toArray(String[]::new);
        Long[] mine = rows.stream().map(Row::id).toArray(Long[]::new);
        Map<String, Long> out = new HashMap<>();
        db.sql("""
                select t.topic || '/' || t.agg,
                       (select min(e.id) from outbox e where e.topic = t.topic and e.aggregate_id = t.agg
                          and e.published_at is null and e.id <> all(?::bigint[]))
                from unnest(?::text[], ?::text[]) as t(topic, agg)""")
                .params(mine, topics, aggs)
                .query((rs, i) -> {
                    long cutoff = rs.getLong(2);
                    if (!rs.wasNull()) {
                        out.put(rs.getString(1), cutoff);
                    }
                    return null;
                }).list();
        return out;
    }

    /** Not attempted (its store is known to be down): try again shortly, without growing the backoff. */
    private final Map<Class<?>, io.micrometer.core.instrument.Timer> handleTimers = new java.util.concurrent.ConcurrentHashMap<>();
    private MeterRegistry meters;

    /** Time spent in each handler, so a slow store shows up as the reason the outbox lags. */
    private io.micrometer.core.instrument.Timer handleTimer(OutboxHandler h) {
        return handleTimers.computeIfAbsent(h.getClass(), c -> io.micrometer.core.instrument.Timer.builder("wokflow.outbox.handle")
                .tag("handler", c.getSimpleName().replaceAll("\\$\\$.*", "")).publishPercentiles(0.5, 0.95).register(meters));
    }

    private void hold(Row r, String reason) {
        db.sql("update outbox set last_error = ?, next_attempt_at = now() + interval '1 second' where id = ?")
                .params(truncate(reason), r.id()).update();
    }

    private void defer(Row r, String error) {
        long backoff = Math.min(60_000L, 250L << Math.min(r.attempts(), 8));
        db.sql("""
                update outbox set attempts = attempts + 1, last_error = ?,
                       next_attempt_at = now() + make_interval(secs => ?) where id = ?""")
                .params(truncate(error), backoff / 1000.0, r.id()).update();
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
