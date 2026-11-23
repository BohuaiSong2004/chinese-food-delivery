package com.wokflow.api.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wokflow.api.config.AppProperties;
import com.wokflow.api.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

class RateLimiterIT extends IntegrationTest {
    @Autowired
    StringRedisTemplate redis;

    RateLimiter enabled() {
        return new RateLimiter(redis, clock, new AppProperties(null, null, null, null, null, new AppProperties.RateLimit(true), null, null, null));
    }

    @Test
    void limitsPerSubjectAndReportsRetryAfter() {
        RateLimiter limiter = enabled();
        String who = unique("ip-");
        clock.setTo(Instant.ofEpochSecond((Instant.now().getEpochSecond() / 60 + 1) * 60)); // start of a minute
        for (int i = 0; i < 5; i++) {
            limiter.check("t", who, 5, Duration.ofMinutes(1));
        }
        assertThatThrownBy(() -> limiter.check("t", who, 5, Duration.ofMinutes(1)))
                .isInstanceOfSatisfying(RetryableApiException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(429);
                    assertThat(e.retryAfterSeconds()).isBetween(1L, 60L);
                });
        limiter.check("t", unique("other-"), 5, Duration.ofMinutes(1)); // other subjects unaffected
    }

    @Test
    void aBurstCannotBeSplitAcrossTheWindowBoundary() {
        RateLimiter limiter = enabled();
        String who = unique("ip-");
        long minute = Instant.now().getEpochSecond() / 60 + 1;
        clock.setTo(Instant.ofEpochSecond(minute * 60 + 50)); // last 10 s of a window
        for (int i = 0; i < 5; i++) {
            limiter.check("b", who, 5, Duration.ofMinutes(1));
        }
        clock.setTo(Instant.ofEpochSecond((minute + 1) * 60 + 5)); // 15 s later, new fixed window
        // A fixed-window limiter would allow 5 more here (10 within 15 s). The sliding estimate still weighs the
        // previous window at 55/60, so 5 x 0.92 = 4.6 and at most one more request gets through.
        int allowed = 0;
        for (int i = 0; i < 5; i++) {
            try {
                limiter.check("b", who, 5, Duration.ofMinutes(1));
                allowed++;
            } catch (RetryableApiException e) {
                break;
            }
        }
        assertThat(allowed).isLessThanOrEqualTo(1);
        clock.setTo(Instant.ofEpochSecond((minute + 1) * 60 + 50));
        limiter.check("b", who, 5, Duration.ofMinutes(1)); // most of the old window has slid out
    }

    @Test
    void peekDoesNotCountOnlyRecordDoes() {
        RateLimiter limiter = enabled();
        String who = unique("acct-");
        for (int i = 0; i < 20; i++) {
            limiter.peek("f", who, 3, Duration.ofMinutes(10)); // successful sign-ins: never limited
        }
        for (int i = 0; i < 3; i++) {
            limiter.record("f", who, 3, Duration.ofMinutes(10)); // wrong passwords
        }
        assertThatThrownBy(() -> limiter.peek("f", who, 3, Duration.ofMinutes(10))).isInstanceOf(RetryableApiException.class);
    }

    @Test
    void disabledLimiterNeverRefuses() {
        var off = new RateLimiter(redis, clock, new AppProperties(null, null, null, null, null, new AppProperties.RateLimit(false), null, null, null));
        for (int i = 0; i < 20; i++) {
            off.check("d", "x", 1, Duration.ofMinutes(1));
        }
    }
}
