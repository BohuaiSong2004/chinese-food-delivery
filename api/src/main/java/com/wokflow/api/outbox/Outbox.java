package com.wokflow.api.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Writes events in the caller's transaction; {@link OutboxPublisher} delivers them after commit. */
@Component
public class Outbox {
    public static final String ORDER_STATUS = "order.status";
    public static final String ORDER_PAID = "order.paid";
    public static final String DISH_CHANGED = "dish.changed";
    public static final String RESTAURANT_CHANGED = "restaurant.changed";
    public static final String DISPATCH_OFFER = "dispatch.offer";

    private final JdbcClient db;
    private final ObjectMapper json;

    private final OutboxWakeup wakeup;

    public Outbox(JdbcClient db, ObjectMapper json, OutboxWakeup wakeup) {
        this.wakeup = wakeup;
        this.db = db;
        this.json = json;
    }

    public void publish(String topic, String aggregateId, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox writes must join the business transaction");
        }
        db.sql("insert into outbox (topic, aggregate_id, payload) values (?, ?, ?::jsonb)")
                .params(topic, aggregateId, json.writeValueAsString(payload)).update();
        wakeup.afterCommit();
    }
}
