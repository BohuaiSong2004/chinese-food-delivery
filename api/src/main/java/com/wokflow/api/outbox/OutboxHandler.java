package com.wokflow.api.outbox;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Consumes outbox events. Must be idempotent: delivery is at least once and may repeat after a failure. */
public interface OutboxHandler {
    Set<String> topics();

    void handle(String topic, String aggregateId, JsonNode payload) throws Exception;
}
