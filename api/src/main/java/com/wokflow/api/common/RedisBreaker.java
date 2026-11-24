package com.wokflow.api.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Circuit breaker for the Redis calls that are optional (menu cache, hot dishes, rate limits).
 * When Redis hangs, each command waits for the 2 s command timeout; without a breaker one menu
 * page paid that three times. After a failure the breaker opens for a few seconds and optional
 * calls are skipped outright; the next call after that is a probe. Calls that must not be lost
 * (outbox publishing, live events) do not go through here: they retry until Redis is back.
 */
@Component
public class RedisBreaker {
    private static final Logger log = LoggerFactory.getLogger(RedisBreaker.class);
    static final Duration OPEN_FOR = Duration.ofSeconds(5);

    private volatile long openUntilNanos;
    private volatile boolean wasOpen;
    private final Counter skipped;
    private final Counter failures;

    public RedisBreaker() {
        this(new SimpleMeterRegistry());
    }

    @Autowired
    public RedisBreaker(MeterRegistry meters) {
        this.skipped = meters.counter("wokflow.redis.breaker", "result", "skipped");
        this.failures = meters.counter("wokflow.redis.breaker", "result", "failed");
        Gauge.builder("wokflow.redis.breaker.open", this, b -> b.isOpen() ? 1 : 0).register(meters);
    }

    public boolean isOpen() {
        return System.nanoTime() - openUntilNanos < 0;
    }

    /** Runs op unless the breaker is open; empty when skipped or when op fails. */
    public <T> Optional<T> call(String what, Supplier<T> op) {
        if (isOpen()) {
            skipped.increment();
            return Optional.empty();
        }
        try {
            T result = op.get();
            if (wasOpen) {
                wasOpen = false;
                log.info("Redis answering again; optional features back on");
            }
            return Optional.ofNullable(result);
        } catch (RuntimeException e) {
            failures.increment();
            openUntilNanos = System.nanoTime() + OPEN_FOR.toNanos();
            if (!wasOpen) {
                log.warn("Redis call '{}' failed; skipping optional Redis features for {} s: {}", what, OPEN_FOR.toSeconds(), e.toString());
            }
            wasOpen = true;
            return Optional.empty();
        }
    }

    /** Opens the breaker after a must-deliver Redis call failed (the caller retries later). */
    public void trip(String what, Throwable e) {
        failures.increment();
        openUntilNanos = System.nanoTime() + OPEN_FOR.toNanos();
        if (!wasOpen) {
            log.warn("Redis call '{}' failed; holding Redis work for {} s: {}", what, OPEN_FOR.toSeconds(), e.toString());
        }
        wasOpen = true;
    }

    /** True for the failures that mean Redis itself is unreachable or hanging (not a bug in the caller). */
    public static boolean isOutage(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.DataAccessResourceFailureException
                    || t instanceof org.springframework.dao.QueryTimeoutException
                    || t instanceof io.lettuce.core.RedisException) {
                return true;
            }
        }
        return false;
    }

    public void run(String what, Runnable op) {
        call(what, () -> {
            op.run();
            return Boolean.TRUE;
        });
    }
}
