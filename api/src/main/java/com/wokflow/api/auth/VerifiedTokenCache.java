package com.wokflow.api.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Remembers access tokens that already passed signature and claim checks, until they expire.
 *
 * <p>A client sends the same token with every request for up to 15 minutes, and verifying an ES256
 * signature was the largest single CPU cost in the API under load (about a quarter of its samples).
 * Only tokens that verified are kept, keyed by their exact text, and a hit is refused once the
 * token's own expiry passes, so nothing is accepted that the full check would refuse. A token signed
 * with a key that is later removed stays usable until it expires, which is also what happens to
 * tokens already in clients' hands; a leaked key calls for a restart, which empties this cache.
 */
public final class VerifiedTokenCache implements JwtDecoder {
    static final int MAX_ENTRIES = 20_000;

    private final JwtDecoder delegate;
    private final Clock clock;
    private final ConcurrentHashMap<String, Jwt> verified = new ConcurrentHashMap<>();

    public VerifiedTokenCache(JwtDecoder delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        Jwt hit = verified.get(token);
        Instant now = clock.instant();
        if (hit != null) {
            if (hit.getExpiresAt() != null && hit.getExpiresAt().isAfter(now)) {
                return hit;
            }
            verified.remove(token);
        }
        Jwt jwt = delegate.decode(token); // throws for anything invalid; nothing is cached then
        if (jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(now)) {
            if (verified.size() >= MAX_ENTRIES) {
                verified.clear(); // crude but bounded; a burst of new sign-ins just re-verifies once
            }
            verified.put(token, jwt);
        }
        return jwt;
    }

    int size() {
        return verified.size();
    }
}
