package com.wokflow.api.live;

import com.wokflow.api.order.OrderStatus;
import com.wokflow.api.order.OrderViews.StatusEvent;
import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.outbox.OutboxHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live updates over SSE, fanned out through one bounded Redis Stream.
 *
 * <p>The outbox appends each committed change to {@link #STREAM} (XADD, trimmed to about
 * {@link #MAX_LEN} entries) with the SSE routing keys it concerns. Every API replica tails the
 * stream and forwards entries to the SSE clients connected to it; the stream entry id is the SSE
 * event id. A client that reconnects sends Last-Event-ID and gets exactly the entries it missed
 * (XRANGE after that id, filtered to its key) instead of a fresh snapshot from Postgres. Only when
 * its id has been trimmed away, or it has none, does it get a snapshot (or a "resync" event for
 * list streams, which makes the client refetch). Clients still compare order versions, so a
 * duplicate during the replay hand-over is harmless.
 */
@Component
public class LiveHub implements OutboxHandler {
    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);
    public static final String STREAM = "wokflow:live";
    static final long MAX_LEN = 20_000;
    static final int MAX_REPLAY = 1_000;
    private static final Pattern STREAM_ID = Pattern.compile("\\d{1,20}-\\d{1,10}");
    private static final Set<OrderStatus> JOB_STATES = Set.of(OrderStatus.ACCEPTED, OrderStatus.READY);

    private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Counter replayed;
    private final Counter resyncs;
    private final Counter snapshots;

    public LiveHub(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        Gauge.builder("wokflow.live.streams", open, AtomicInteger::get).register(meters);
        this.replayed = meters.counter("wokflow.live.reconnects", "result", "replayed");
        this.resyncs = meters.counter("wokflow.live.reconnects", "result", "resync");
        this.snapshots = meters.counter("wokflow.live.reconnects", "result", "snapshot");
    }

    @org.springframework.context.annotation.Configuration
    static class Wiring {
        /** Tails the stream from "now" on a platform thread. */
        @org.springframework.context.annotation.Bean(initMethod = "start", destroyMethod = "stop")
        StreamMessageListenerContainer<String, MapRecord<String, String, String>> liveTail(RedisConnectionFactory cf, LiveHub hub) {
            var options = StreamMessageListenerContainerOptions.builder()
                    // XREAD BLOCK must return before the client's 2 s command timeout, or every idle poll is an "error"
                    .pollTimeout(Duration.ofMillis(1000))
                    .batchSize(500)
                    .executor(Executors.newSingleThreadExecutor(r -> Thread.ofPlatform().daemon().name("live-tail").unstarted(r)))
                    .errorHandler(e -> log.warn("Live stream read failed (retrying): {}", e.toString()))
                    .build();
            var container = StreamMessageListenerContainer.create(cf, options);
            // Keep reading after an error (e.g. Redis restarting): by default the subscription would be cancelled
            // and this replica's clients would silently stop getting updates.
            // lastConsumed(), not latest(): latest() reads from "$" on every poll and silently skips anything
            // appended between two polls; lastConsumed() starts at "$" and then continues from the last id read.
            container.register(StreamMessageListenerContainer.StreamReadRequest.builder(StreamOffset.create(STREAM, ReadOffset.lastConsumed()))
                    .cancelOnError(e -> false).build(), hub::deliver);
            return container;
        }
    }

    // ---------------------------------------------------------------- outbox -> redis stream

    @Override
    public Set<String> topics() { return Set.of(Outbox.ORDER_STATUS, Outbox.DISPATCH_OFFER); }

    @Override
    public boolean usesRedis() { return true; }

    @Override
    public void handle(String topic, String aggregateId, JsonNode payload) {
        if (Outbox.DISPATCH_OFFER.equals(topic)) {
            publish("offer", List.of("rider:" + payload.get("riderId").asLong()), payload.toString());
        } else {
            publish("status", keys(json.treeToValue(payload, StatusEvent.class)), payload.toString());
        }
    }

    @Override
    public boolean batches() { return true; }

    /** All of a batch's events in one pipelined round trip, in order (one XADD each). */
    @Override
    public void handleBatch(List<com.wokflow.api.outbox.OutboxHandler.Event> events) {
        List<MapRecord<String, String, String>> records = new ArrayList<>(events.size());
        for (var e : events) {
            Map<String, String> fields = new LinkedHashMap<>();
            if (Outbox.DISPATCH_OFFER.equals(e.topic())) {
                fields.put("type", "offer");
                fields.put("keys", "rider:" + e.payload().get("riderId").asLong());
            } else {
                fields.put("type", "status");
                fields.put("keys", String.join(",", keys(json.treeToValue(e.payload(), StatusEvent.class))));
            }
            fields.put("data", e.payload().toString());
            records.add(StreamRecords.string(fields).withStreamKey(STREAM));
        }
        var options = org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions.maxlen(MAX_LEN).approximateTrimming(true);
        redis.executePipelined(new org.springframework.data.redis.core.SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(org.springframework.data.redis.core.RedisOperations<K, V> ops) {
                var stream = ((org.springframework.data.redis.core.RedisOperations<String, String>) ops).opsForStream();
                for (var r : records) {
                    stream.add(r, options);
                }
                return null;
            }
        });
    }

    /** Appends one event (also used for ephemeral events such as rider positions). */
    public RecordId publish(String type, List<String> keys, String data) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", type);
        fields.put("keys", String.join(",", keys));
        fields.put("data", data);
        return redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(STREAM),
                org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions.maxlen(MAX_LEN).approximateTrimming(true));
    }

    static List<String> keys(StatusEvent e) {
        List<String> keys = new ArrayList<>(List.of("order:" + e.orderId(), "restaurant:" + e.restaurantId(),
                "customer:" + e.customerId()));
        if (e.riderId() != null) {
            keys.add("rider:" + e.riderId());
        }
        if (JOB_STATES.contains(e.status()) || (e.previous() != null && JOB_STATES.contains(e.previous()))) {
            keys.add("jobs");
        }
        return keys;
    }

    // ---------------------------------------------------------------- redis stream -> sse

    void deliver(MapRecord<String, String, String> record) {
        Map<String, String> f = record.getValue();
        String id = record.getId().getValue();
        for (String key : f.getOrDefault("keys", "").split(",")) {
            send(key, event(id, f));
        }
    }

    private static SseEmitter.SseEventBuilder event(String id, Map<String, String> f) {
        return SseEmitter.event().id(id).name(f.get("type")).data(f.get("data"));
    }

    /**
     * Ends every open stream as soon as shutdown begins. Without this, Tomcat's graceful shutdown
     * waited its full 30 s for streams that never finish on their own, and then cut them anyway.
     * Browsers reconnect after the 2 s retry hint, land on another replica and resume from their
     * Last-Event-ID, so a rolling deploy costs customers a two-second pause, not a lost update.
     */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void closeAll() {
        int n = 0;
        for (Set<SseEmitter> set : streams.values()) {
            for (SseEmitter e : set) {
                if (set.remove(e)) {
                    open.decrementAndGet();
                    n++;
                }
                try {
                    e.complete();
                } catch (RuntimeException ignored) {
                    // already finished
                }
            }
        }
        if (n > 0) {
            log.info("Shutting down: closed {} live streams so their clients reconnect elsewhere", n);
        }
    }

    /**
     * Opens a stream. With a Last-Event-ID that is still in the Redis stream, the missed events are
     * replayed; otherwise the snapshot (if any) is sent, or "resync" for list streams.
     */
    public SseEmitter open(String key, Duration lifetime, Supplier<Object> snapshot, String lastEventId) {
        SseEmitter emitter = new SseEmitter(lifetime.toMillis());
        Set<SseEmitter> set = streams.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        Runnable remove = () -> {
            if (set.remove(emitter)) {
                open.decrementAndGet();
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            remove.run();
            emitter.complete();
        });
        emitter.onError(t -> remove.run());
        try {
            emitter.send(SseEmitter.event().comment("connected").reconnectTime(2000));
            // Registered before the replay: an event published meanwhile may arrive twice, never not at all.
            set.add(emitter);
            open.incrementAndGet();
            boolean resumed = lastEventId != null && STREAM_ID.matcher(lastEventId).matches() && replay(emitter, key, lastEventId);
            if (!resumed) {
                Object snap = snapshot == null ? null : snapshot.get();
                if (snap != null) {
                    snapshots.increment();
                    emitter.send(SseEmitter.event().name("snapshot").data(json.writeValueAsString(snap)));
                } else if (lastEventId != null) {
                    resyncs.increment();
                    emitter.send(SseEmitter.event().name("resync").data("{}"));
                }
            }
        } catch (IOException e) {
            remove.run();
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** Sends what the client missed, or returns false if its position is no longer in the stream. */
    private boolean replay(SseEmitter emitter, String key, String lastId) throws IOException {
        var first = redis.opsForStream().range(STREAM, Range.unbounded(), Limit.limit().count(1));
        if (first == null || first.isEmpty() || compare(first.getFirst().getId().getValue(), lastId) > 0) {
            return false; // trimmed past the client's position (or the stream was lost): it must resync
        }
        var missed = redis.opsForStream().range(STREAM, Range.of(Range.Bound.exclusive(lastId), Range.Bound.unbounded()),
                Limit.limit().count(MAX_REPLAY));
        if (missed == null) {
            return false;
        }
        if (missed.size() == MAX_REPLAY) {
            return false; // too far behind: a snapshot is cheaper than replaying everything
        }
        for (var r : missed) {
            Map<String, String> f = new LinkedHashMap<>();
            r.getValue().forEach((k, v) -> f.put(String.valueOf(k), String.valueOf(v)));
            if (List.of(f.getOrDefault("keys", "").split(",")).contains(key)) {
                emitter.send(event(r.getId().getValue(), f));
            }
        }
        replayed.increment();
        return true;
    }

    /** Orders Redis stream ids ("ms-seq"). */
    static int compare(String a, String b) {
        String[] x = a.split("-");
        String[] y = b.split("-");
        int c = Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0]));
        return c != 0 ? c : Long.compare(Long.parseLong(x[1]), Long.parseLong(y[1]));
    }

    private void send(String key, SseEmitter.SseEventBuilder event) {
        Set<SseEmitter> set = streams.get(key);
        if (set == null) {
            return;
        }
        for (SseEmitter em : set) {
            try {
                em.send(event);
            } catch (Exception e) {
                if (set.remove(em)) {
                    open.decrementAndGet();
                }
            }
        }
    }

    /** Keeps proxies from idling the connection out and finds dead clients. */
    @Scheduled(fixedDelayString = "PT20S")
    void heartbeat() {
        for (String key : streams.keySet()) {
            send(key, SseEmitter.event().comment("ping"));
        }
        streams.entrySet().removeIf(e -> e.getValue().isEmpty());
    }

    public int openStreams() { return open.get(); }
}
