package com.wokflow.api.outbox;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Consumes outbox events. Must be idempotent: delivery is at least once and may repeat after a failure. */
public interface OutboxHandler {
    Set<String> topics();

    void handle(String topic, String aggregateId, JsonNode payload) throws Exception;

    /** True if handling needs Redis: the publisher then holds these events back while Redis is known to be down. */
    default boolean usesRedis() {
        return false;
    }
}
