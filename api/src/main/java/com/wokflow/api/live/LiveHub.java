package com.wokflow.api.live;

import com.wokflow.api.order.OrderStatus;
import com.wokflow.api.order.OrderViews.StatusEvent;
import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.outbox.OutboxHandler;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live order status. The outbox publishes each committed change to a Redis
 * channel; every API replica subscribes and forwards it to the SSE clients
 * connected to it. Pub/sub is at-most-once, so clients also get a fresh
 * snapshot from Postgres whenever they (re)connect, and each event carries the
 * order version so a client never moves backwards to an older status.
 */
@Component
public class LiveHub implements OutboxHandler, MessageListener {
    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);
    public static final String CHANNEL = "wokflow:orders";
    private static final Set<OrderStatus> JOB_STATES = Set.of(OrderStatus.ACCEPTED, OrderStatus.READY);

    private final Map<String, Set<SseEmitter>> streams = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public LiveHub(StringRedisTemplate redis, ObjectMapper json, MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        Gauge.builder("wokflow.live.streams", open, AtomicInteger::get).register(meters);
    }

    @Configuration
    static class Wiring {
        @Bean
        RedisMessageListenerContainer liveListener(RedisConnectionFactory cf, LiveHub hub) {
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(cf);
            c.addMessageListener(hub, new ChannelTopic(CHANNEL));
            c.setRecoveryInterval(1000L);
            return c;
        }
    }

    // ---------------------------------------------------------------- outbox -> redis

    @Override
    public Set<String> topics() { return Set.of(Outbox.ORDER_STATUS); }

    @Override
    public void handle(String topic, String aggregateId, JsonNode payload) {
        redis.convertAndSend(CHANNEL, payload.toString());
    }

    // ---------------------------------------------------------------- redis -> sse

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            StatusEvent e = json.readValue(message.getBody(), StatusEvent.class);
            String data = new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8);
            List<String> keys = new ArrayList<>(List.of("order:" + e.orderId(), "restaurant:" + e.restaurantId(),
                    "customer:" + e.customerId()));
            if (e.riderId() != null) {
                keys.add("rider:" + e.riderId());
            }
            if (JOB_STATES.contains(e.status()) || (e.previous() != null && JOB_STATES.contains(e.previous()))) {
                keys.add("jobs");
            }
            for (String k : keys) {
                send(k, SseEmitter.event().name("status").id(Integer.toString(e.version())).data(data));
            }
        } catch (Exception ex) {
            log.warn("Bad live event: {}", ex.toString());
        }
    }

    public SseEmitter open(String key, Duration lifetime, Object snapshot) {
        SseEmitter emitter = new SseEmitter(lifetime.toMillis());
        Set<SseEmitter> set = streams.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        open.incrementAndGet();
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
            if (snapshot != null) {
                emitter.send(SseEmitter.event().name("snapshot").data(json.writeValueAsString(snapshot)));
            }
        } catch (IOException e) {
            remove.run();
            emitter.completeWithError(e);
        }
        return emitter;
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
