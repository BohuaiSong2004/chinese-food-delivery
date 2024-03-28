package com.wokflow.api.common;

import com.wokflow.api.config.AppProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter shared by all replicas (Redis). The estimate is
 * current-window count + previous-window count x the part of the previous
 * window still inside the sliding window, so a burst cannot be split across a
 * fixed window boundary to get twice the limit. Fails open if Redis is down:
 * losing rate limiting briefly is better than refusing every sign-in.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local cur = tonumber(redis.call('GET', KEYS[1]) or '0')
            local prev = tonumber(redis.call('GET', KEYS[2]) or '0')
            local limit = tonumber(ARGV[1])
            local weight = tonumber(ARGV[2])
            local estimate = cur + prev * weight
            if estimate >= limit then
              return {0, math.floor(estimate)}
            end
            if ARGV[4] == 'peek' then
              return {1, math.floor(estimate)}
            end
            cur = redis.call('INCR', KEYS[1])
            if cur == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
            return {1, math.floor(cur + prev * weight)}
            """, List.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final boolean enabled;

    public RateLimiter(StringRedisTemplate redis, Clock clock, AppProperties props) {
        this.redis = redis;
        this.clock = clock;
        this.enabled = props.rateLimit().enabled();
    }

    /** Counts this attempt; throws 429 with Retry-After when {@code subject} exceeded {@code limit} per {@code window}. */
    public void check(String name, String subject, int limit, Duration window) {
        run(name, subject, limit, window, "count");
    }

    /** Throws 429 if already over the limit, without counting this attempt (pair with {@link #record}). */
    public void peek(String name, String subject, int limit, Duration window) {
        run(name, subject, limit, window, "peek");
    }

    /** Counts an attempt that turned out to matter (e.g. a wrong password). */
    public void record(String name, String subject, int limit, Duration window) {
        try {
            run(name, subject, Integer.MAX_VALUE, window, "count");
        } catch (RetryableApiException ignored) {
            // cannot happen with an unbounded limit
        }
    }

    private void run(String name, String subject, int limit, Duration window, String mode) {
        if (!enabled) {
            return;
        }
        long now = clock.millis();
        long size = window.toMillis();
        long slot = now / size;
        double elapsed = (now % size) / (double) size;
        String base = "rl:" + name + ":" + subject + ":";
        List<?> res;
        try {
            res = redis.execute(SCRIPT, List.of(base + slot, base + (slot - 1)),
                    Integer.toString(limit), Double.toString(1.0 - elapsed), Long.toString(size * 2), mode);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
            return;
        }
        if (res != null && ((Number) res.get(0)).longValue() == 0) {
            long retry = (long) Math.ceil((size - now % size) / 1000.0);
            throw new RetryableApiException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited",
                    "Too many attempts, try again shortly", retry);
        }
    }
}
