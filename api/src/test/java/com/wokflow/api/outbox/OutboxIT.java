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
    void anEventWaitsWhileAnEarlierOneOfTheSameAggregateIsBeingPublished() throws Exception {
        String id = unique("ord-");
        String other = unique("ord-");
        // written directly and not yet due, so the running publisher leaves them alone until the test is ready
        for (var row : java.util.List.of(new Object[] {id, 1}, new Object[] {id, 2}, new Object[] {other, 1})) {
            db.sql("insert into outbox (topic, aggregate_id, payload, next_attempt_at) values ('test.ordered', ?, jsonb_build_object('v', ?), now() + interval '1 hour')")
                    .params(row[0], row[1]).update();
        }
        long first = db.sql("select min(id) from outbox where aggregate_id = ?").param(id).query(Long.class).single();
        // another replica has claimed v1 and is still publishing it
        var holder = new java.util.concurrent.CompletableFuture<Void>();
        var claimed = new java.util.concurrent.CountDownLatch(1);
        Thread replica = Thread.ofPlatform().start(() -> tx.executeWithoutResult(s -> {
            db.sql("select id from outbox where id = ? for update").param(first).query(Long.class).single();
            claimed.countDown();
            holder.join();
        }));
        claimed.await();
        try {
            db.sql("update outbox set next_attempt_at = now() where aggregate_id in (?, ?) and id <> ?").params(id, other, first).update();
            outbox.drainOnce();
            waitUntil(() -> published(other) == 1); // other orders are not held up
            outbox.drainOnce();
            assertThat(published(id)).as("v2 must not overtake v1").isZero();
        } finally {
            holder.complete(null);
            replica.join();
        }
        db.sql("update outbox set next_attempt_at = now() where id = ?").param(first).update();
        for (int i = 0; i < 3; i++) {
            outbox.drainOnce();
        }
        assertThat(published(id)).isEqualTo(2);
        assertThat(db.sql("select string_agg(payload->>'v', ',' order by published_at, id) from outbox where aggregate_id = ?")
                .param(id).query(String.class).single()).isEqualTo("1,2");
    }

    @Test
    void aBatchStopsAtAGapLeftByAnotherReplica() throws Exception {
        String id = unique("gap-");
        for (int v = 1; v <= 3; v++) {
            db.sql("insert into outbox (topic, aggregate_id, payload, next_attempt_at) values ('test.ordered', ?, jsonb_build_object('v', ?), now() + interval '1 hour')")
                    .params(id, v).update();
        }
        long second = db.sql("select min(id) + 1 from outbox where aggregate_id = ?").param(id).query(Long.class).single();
        // another replica claimed v2 (and set it aside, still holding the row) while v1 and v3 are free
        var holder = new java.util.concurrent.CompletableFuture<Void>();
        var claimed = new java.util.concurrent.CountDownLatch(1);
        Thread replica = Thread.ofPlatform().start(() -> tx.executeWithoutResult(s -> {
            db.sql("select id from outbox where id = ? for update").param(second).query(Long.class).single();
            claimed.countDown();
            holder.join();
        }));
        claimed.await();
        try {
            db.sql("update outbox set next_attempt_at = now() where aggregate_id = ? and id <> ?").params(id, second).update();
            outbox.drainOnce();
            waitUntil(() -> published(id) >= 1);
            outbox.drainOnce();
            assertThat(published(id)).as("v1 goes out, v3 must wait for v2").isEqualTo(1);
        } finally {
            holder.complete(null);
            replica.join();
        }
        db.sql("update outbox set next_attempt_at = now() where id = ?").param(second).update();
        waitUntil(() -> published(id) == 3);
        assertThat(db.sql("select string_agg(payload->>'v', ',' order by published_at, id) from outbox where aggregate_id = ?")
                .param(id).query(String.class).single()).isEqualTo("1,2,3");
    }

    long published(String aggregate) {
        return db.sql("select count(*) from outbox where aggregate_id = ? and published_at is not null").param(aggregate)
                .query(Long.class).single();
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
