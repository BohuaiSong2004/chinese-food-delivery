package com.wokflow.api.outbox;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Starts delivery as soon as a transaction that wrote outbox rows commits.
 *
 * <p>The local publisher is woken in-process. Other replicas hear about it through at most one
 * {@code NOTIFY outbox} per {@link #COALESCE} window, sent in its own tiny transaction. NOTIFY used to
 * come from an insert trigger, i.e. from inside every business transaction, and Postgres serializes
 * the commits of all transactions that notified: under load every order waited for every other
 * order's WAL flush.
 */
@Component
public class OutboxWakeup {
    private static final Logger log = LoggerFactory.getLogger(OutboxWakeup.class);
    static final Duration COALESCE = Duration.ofMillis(50);
    private static final Object REGISTERED = new Object();

    private final JdbcClient db;
    private final AtomicBoolean notifyPending = new AtomicBoolean();
    private volatile Runnable local = () -> { };

    public OutboxWakeup(JdbcClient db) {
        this.db = db;
    }

    void onWake(Runnable r) {
        this.local = r;
    }

    /** Called inside a transaction that wrote to the outbox; acts once, after it commits. */
    void afterCommit() {
        if (TransactionSynchronizationManager.hasResource(REGISTERED)) {
            return;
        }
        TransactionSynchronizationManager.bindResource(REGISTERED, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(REGISTERED);
                if (status == STATUS_COMMITTED) {
                    committed();
                }
            }
        });
    }

    void committed() {
        local.run();
        if (notifyPending.compareAndSet(false, true)) {
            Thread.ofVirtual().name("outbox-notify").start(() -> {
                try {
                    Thread.sleep(COALESCE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                notifyPending.set(false);
                try {
                    db.sql("select pg_notify('outbox', '')").query(Object.class).optional();
                } catch (RuntimeException e) {
                    log.debug("Outbox NOTIFY failed (peers fall back to polling): {}", e.toString());
                }
            });
        }
    }
}
