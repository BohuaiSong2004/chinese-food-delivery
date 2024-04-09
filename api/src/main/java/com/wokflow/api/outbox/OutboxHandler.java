package com.wokflow.api.outbox;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Consumes outbox events. Must be idempotent: delivery is at least once and may repeat after a failure. */
public interface OutboxHandler {
    Set<String> topics();

    void handle(String topic, String aggregateId, JsonNode payload) throws Exception;

    record Event(String topic, String aggregateId, JsonNode payload) {}

    /**
     * True for a handler that sends a whole batch at once (one Redis round trip instead of one per
     * event). The publisher then calls {@link #handleBatch} once per batch, with the events in order.
     */
    default boolean batches() {
        return false;
    }

    default void handleBatch(java.util.List<Event> events) throws Exception {
        for (Event e : events) {
            handle(e.topic(), e.aggregateId(), e.payload());
        }
    }

    /** True if handling needs Redis: the publisher then holds these events back while Redis is known to be down. */
    default boolean usesRedis() {
        return false;
    }
}
