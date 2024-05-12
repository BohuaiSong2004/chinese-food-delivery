package com.wokflow.api.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in a transaction and retries it when Postgres aborts it
 * with a serialization failure (40001) or a deadlock (40P01). The code is
 * written so that neither should happen (rows are always locked in id order and
 * state changes are single-row compare-and-set updates); the counters make that
 * claim observable under load.
 */
@Component
public class Tx {
    private static final int MAX_ATTEMPTS = 4;
    private final TransactionTemplate template;
    private final TransactionTemplate readOnly;
    private final Counter deadlocks;
    private final Counter serialization;

    public Tx(TransactionTemplate template, MeterRegistry meters) {
        this.template = template;
        this.readOnly = new TransactionTemplate(java.util.Objects.requireNonNull(template.getTransactionManager()));
        this.readOnly.setReadOnly(true);
        this.deadlocks = meters.counter("wokflow.tx.retries", "reason", "deadlock");
        this.serialization = meters.counter("wokflow.tx.retries", "reason", "serialization");
    }

    public <T> T run(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return template.execute(status -> work.get());
            } catch (DataAccessException e) {
                String state = sqlState(e);
                boolean retryable = "40P01".equals(state) || "40001".equals(state);
                if (!retryable || attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                ("40P01".equals(state) ? deadlocks : serialization).increment();
                try {
                    Thread.sleep(5L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * A read made of several queries, on one pooled connection and one snapshot. Outside a
     * transaction every statement borrows and returns its own connection, so under pool pressure
     * a seven-query order page queued for a connection seven times.
     */
    public <T> T read(Supplier<T> work) {
        return readOnly.execute(status -> work.get());
    }

    public void runVoid(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }

    static String sqlState(Throwable t) {
        while (t != null) {
            if (t instanceof SQLException s && s.getSQLState() != null) {
                return s.getSQLState();
            }
            t = t.getCause();
        }
        return null;
    }
}
