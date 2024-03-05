package com.wokflow.api.auth;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.RateLimiter;
import com.wokflow.api.common.Tx;
import com.wokflow.api.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    /** A second refresh with the same token inside this window is a concurrent tab, not theft. */
    static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    public record Session(UserRepository.User user, List<Long> restaurantIds, TokenService.AccessToken access,
                          String refreshToken) {}

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final TokenService tokens;
    private final RateLimiter limiter;
    private final Tx tx;
    private final Clock clock;
    private final AppProperties props;
    private final Argon2PasswordEncoder hasher = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = hasher.encode("timing-equalizer-not-a-password");
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, TokenService tokens,
                       RateLimiter limiter, Tx tx, Clock clock, AppProperties props) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.limiter = limiter;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    public String hash(String password) { return hasher.encode(password); }

    public Session signUp(String email, String password, String name, String clientIp) {
        limiter.check("signup", clientIp, 20, Duration.ofHours(1));
        String normalized = normalize(email);
        String hash = hasher.encode(password);
        long id = users.insert(normalized, hash, name.strip(), Role.CUSTOMER)
                .orElseThrow(() -> ApiException.conflict("email_taken", "An account with this email already exists"));
        UserRepository.User user = users.findById(id).orElseThrow();
        return start(user);
    }

    public Session signIn(String email, String password, String clientIp) {
        String normalized = normalize(email);
        limiter.check("signin-ip", clientIp, 60, Duration.ofMinutes(10));
        limiter.check("signin-email", normalized, 10, Duration.ofMinutes(10));
        var user = users.findByEmail(normalized);
        // Hash even when the account does not exist so response time does not reveal which emails are registered.
        boolean ok = hasher.matches(password, user.map(UserRepository.User::passwordHash).orElse(dummyHash));
        if (user.isEmpty() || !ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is incorrect");
        }
        return start(user.get());
    }

    private Session start(UserRepository.User user) {
        String refresh = newRefreshToken(user.id(), UUID.randomUUID());
        List<Long> rids = users.restaurantIds(user.id());
        return new Session(user, rids, tokens.issue(user, rids), refresh);
    }

    private String newRefreshToken(long userId, UUID family) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        refreshTokens.insert(UUID.randomUUID(), userId, family, sha256(token), clock.instant().plus(props.jwt().refreshTtl()));
        return token;
    }

    /**
     * Rotates a refresh token. Using an already-rotated token after the grace
     * window means it was copied: the whole family is revoked. The revoke must
     * commit even though the caller gets a 401, so it runs in its own
     * transaction and the error is raised afterwards.
     */
    public Session refresh(String presented) {
        if (presented == null || presented.isBlank()) {
            throw unauthorized();
        }
        Instant now = clock.instant();
        record Outcome(Session session, boolean reused) {}
        Outcome outcome = tx.run(() -> {
            var stored = refreshTokens.lockByHash(sha256(presented)).orElse(null);
            if (stored == null || stored.revokedAt() != null || stored.expiresAt().isBefore(now)) {
                return new Outcome(null, false);
            }
            var user = users.findById(stored.userId()).orElse(null);
            if (user == null) {
                return new Outcome(null, false);
            }
            List<Long> rids = users.restaurantIds(user.id());
            if (stored.rotatedAt() != null) {
                if (stored.rotatedAt().plus(REUSE_GRACE).isAfter(now)) {
                    // Concurrent refresh from another tab: the browser already holds the replacement cookie.
                    return new Outcome(new Session(user, rids, tokens.issue(user, rids), null), false);
                }
                refreshTokens.revokeFamily(stored.familyId(), now);
                return new Outcome(null, true);
            }
            refreshTokens.markRotated(stored.id(), now);
            String next = newRefreshToken(user.id(), stored.familyId());
            return new Outcome(new Session(user, rids, tokens.issue(user, rids), next), false);
        });
        if (outcome.session() == null) {
            throw outcome.reused()
                    ? new ApiException(HttpStatus.UNAUTHORIZED, "refresh_reused", "Session ended, sign in again")
                    : unauthorized();
        }
        return outcome.session();
    }

    public void signOut(String presented) {
        if (presented == null || presented.isBlank()) {
            return;
        }
        tx.runVoid(() -> refreshTokens.lockByHash(sha256(presented))
                .ifPresent(t -> refreshTokens.revokeFamily(t.familyId(), clock.instant())));
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void purgeExpired() {
        refreshTokens.deleteExpired(clock.instant().minus(Duration.ofDays(1)));
    }

    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh", "Session expired, sign in again");
    }
}
