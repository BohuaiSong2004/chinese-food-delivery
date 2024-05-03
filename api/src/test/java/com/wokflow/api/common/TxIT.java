package com.wokflow.api.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.wokflow.api.support.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TxIT extends IntegrationTest {
    @Autowired
    Tx tx;
    @Autowired
    MeterRegistry meters;

    /** If a deadlock ever happens, the victim is retried and both transactions still commit. */
    @Test
    void deadlockVictimIsRetried() throws Exception {
        var k = newKitchen(10);
        double before = meters.counter("wokflow.tx.retries", "reason", "deadlock").count();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger firstAttempts = new AtomicInteger();
        Runnable a = () -> tx.runVoid(() -> {
            db.sql("update dishes set price_cents = price_cents + 1 where id = ?").param(k.plainDish()).update();
            if (firstAttempts.getAndIncrement() == 0) await(barrier);
            db.sql("update dishes set price_cents = price_cents + 1 where id = ?").param(k.limitedDish()).update();
        });
        AtomicInteger secondAttempts = new AtomicInteger();
        Runnable b = () -> tx.runVoid(() -> {
            db.sql("update dishes set price_cents = price_cents + 1 where id = ?").param(k.limitedDish()).update();
            if (secondAttempts.getAndIncrement() == 0) await(barrier);
            db.sql("update dishes set price_cents = price_cents + 1 where id = ?").param(k.plainDish()).update();
        });
        CompletableFuture.allOf(CompletableFuture.runAsync(a), CompletableFuture.runAsync(b)).get();
        assertThat(meters.counter("wokflow.tx.retries", "reason", "deadlock").count()).isEqualTo(before + 1);
        assertThat(db.sql("select price_cents from dishes where id = ?").param(k.plainDish()).query(Integer.class).single()).isEqualTo(1002);
    }

    static void await(CyclicBarrier b) {
        try {
            b.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
