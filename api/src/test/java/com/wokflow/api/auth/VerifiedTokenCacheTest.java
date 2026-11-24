package com.wokflow.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class VerifiedTokenCacheTest {
    Instant now = Instant.parse("2026-11-24T12:00:00Z");
    final AtomicInteger verifications = new AtomicInteger();

    JwtDecoder fake() {
        return token -> {
            verifications.incrementAndGet();
            if (token.startsWith("bad")) {
                throw new BadJwtException("signature");
            }
            return Jwt.withTokenValue(token).header("alg", "ES256").subject("7")
                    .issuedAt(now).expiresAt(Instant.parse("2026-11-24T12:15:00Z")).build();
        };
    }

    @Test
    void aVerifiedTokenIsCheckedOnceUntilItExpires() {
        var clock = new java.util.concurrent.atomic.AtomicReference<>(now);
        var cache = new VerifiedTokenCache(fake(), new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return clock.get(); }
        });
        for (int i = 0; i < 100; i++) {
            assertThat(cache.decode("good").getSubject()).isEqualTo("7");
        }
        assertThat(verifications).hasValue(1);

        clock.set(now.plus(Duration.ofMinutes(16))); // past expiry: the full check runs again (and would refuse)
        cache.decode("good");
        assertThat(verifications).hasValue(2);
    }

    @Test
    void invalidTokensAreNeverCached() {
        var cache = new VerifiedTokenCache(fake(), Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> cache.decode("bad-1")).isInstanceOf(BadJwtException.class);
        assertThatThrownBy(() -> cache.decode("bad-1")).isInstanceOf(BadJwtException.class);
        assertThat(verifications).hasValue(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    void theCacheStaysBounded() {
        var cache = new VerifiedTokenCache(fake(), Clock.fixed(now, ZoneOffset.UTC));
        for (int i = 0; i <= VerifiedTokenCache.MAX_ENTRIES; i++) {
            cache.decode("t" + i);
        }
        assertThat(cache.size()).isLessThanOrEqualTo(VerifiedTokenCache.MAX_ENTRIES);
    }
}
