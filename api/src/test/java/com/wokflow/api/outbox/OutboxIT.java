package com.wokflow.api.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxIT extends IntegrationTest {
    @Autowired
    Outbox writer;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    FlakyHandler flaky;

    @Test
    void failedDeliveriesAreRetriedWithBackoffUntilTheySucceed() throws Exception {
        flaky.failuresLeft.set(2);
        String id = unique("agg-");
        tx.executeWithoutResult(s -> writer.publish("test.flaky", id, Map.of("n", 1)));
        for (int i = 0; i < 50 && flaky.handled.isEmpty(); i++) {
            db.sql("update outbox set next_attempt_at = now() where aggregate_id = ?").param(id).update(); // skip the backoff wait
            outbox.drainOnce();
            Thread.sleep(20);
        }
        assertThat(flaky.handled).contains(id);
        var row = db.sql("select attempts, last_error, published_at is not null from outbox where aggregate_id = ?").param(id)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getBoolean(3)}).single();
        assertThat(row[0]).isEqualTo(2);
        assertThat((String) row[1]).contains("flaky");
        assertThat(row[2]).isEqualTo(true);
    }

    @Test
    void eventsCanOnlyBeWrittenInsideTheBusinessTransaction() {
        assertThatThrownBy(() -> writer.publish("test.flaky", "x", Map.of())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rolledBackChangesPublishNothing() {
        String id = unique("rb-");
        try {
            tx.executeWithoutResult(s -> {
                writer.publish("test.flaky", id, Map.of());
                throw new IllegalStateException("business rule failed");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(db.sql("select count(*) from outbox where aggregate_id = ?").param(id).query(Integer.class).single()).isZero();
    }
}
