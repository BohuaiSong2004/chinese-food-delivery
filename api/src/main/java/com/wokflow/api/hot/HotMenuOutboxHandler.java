package com.wokflow.api.hot;

import com.wokflow.api.outbox.Outbox;
import com.wokflow.api.outbox.OutboxHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
class HotMenuOutboxHandler implements OutboxHandler {
    private final HotMenuService hot;

    HotMenuOutboxHandler(HotMenuService hot) { this.hot = hot; }

    @Override
    public Set<String> topics() { return Set.of(Outbox.ORDER_PAID); }

    @Override
    public boolean usesRedis() { return true; }

    @Override
    public void handle(String topic, String aggregateId, JsonNode p) {
        Map<Long, Integer> qty = new LinkedHashMap<>();
        for (JsonNode item : p.get("items")) {
            qty.merge(item.get("dishId").asLong(), item.get("quantity").asInt(), Integer::sum);
        }
        hot.record(UUID.fromString(p.get("orderId").asText()), p.get("restaurantId").asLong(), qty,
                Instant.parse(p.get("paidAt").asText()));
    }
}
