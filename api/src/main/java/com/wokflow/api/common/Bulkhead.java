package com.wokflow.api.common;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * Caps how many callers run a section at once. With virtual threads every
 * request gets a thread, so without this a rush of placements would all grab
 * pool connections and then wait on the same hot stock row, starving webhooks
 * and reads. Queued callers wait in memory (cheap) and get a 503 with
 * Retry-After if the wait is too long, instead of a connection-timeout 500.
 */
public class Bulkhead {
    private final Semaphore permits;
    private final Duration maxWait;
    private final AtomicInteger waiting = new AtomicInteger();
    private final String name;

    public Bulkhead(String name, int concurrency, Duration maxWait, MeterRegistry meters) {
        this.name = name;
        this.permits = new Semaphore(concurrency, true);
        this.maxWait = maxWait;
        Gauge.builder("wokflow.bulkhead.waiting", waiting, AtomicInteger::get).tag("name", name).register(meters);
    }

    public <T> T run(Supplier<T> work) {
        waiting.incrementAndGet();
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        } finally {
            waiting.decrementAndGet();
        }
        if (!acquired) {
            throw new RetryableApiException(HttpStatus.SERVICE_UNAVAILABLE, "busy",
                    "We are taking a lot of orders right now; please try again in a moment", 2);
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }

    public String name() { return name; }
}
