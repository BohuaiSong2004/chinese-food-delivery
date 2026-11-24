package com.wokflow.api.auth;

import com.wokflow.api.common.ApiException;
import com.wokflow.api.common.RateLimiter;
import com.wokflow.api.common.Tx;
import com.wokflow.api.config.AppProperties;
import com.wokflow.api.mail.Mailer;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Forgotten and changed passwords.
 *
 * <ul>
 *   <li>"Forgot password" always answers the same way, and the email is sent on a background
 *       thread, so neither the response nor its timing reveals whether an account exists.</li>
 *   <li>The link carries a random 256-bit token in the URL fragment (never sent to a server or
 *       written to access logs); only its SHA-256 is stored; it is single-use and lasts 30 minutes.</li>
 *   <li>Setting a new password ends every session (all refresh-token families) and invalidates
 *       other outstanding links. Access tokens already issued live out their 15 minutes: the price
 *       of stateless verification, documented rather than hidden.</li>
 * </ul>
 */
@Service
public class PasswordService {
    static final Duration LINK_TTL = Duration.ofMinutes(30);

    private final JdbcClient db;
    private final AuthService auth;
    private final RefreshTokenRepository refreshTokens;
    private final RateLimiter limiter;
    private final Mailer mailer;
    private final Tx tx;
    private final Clock clock;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public PasswordService(JdbcClient db, AuthService auth, RefreshTokenRepository refreshTokens, RateLimiter limiter,
                           Mailer mailer, Tx tx, Clock clock, AppProperties props) {
        this.db = db;
        this.auth = auth;
        this.refreshTokens = refreshTokens;
        this.limiter = limiter;
        this.mailer = mailer;
        this.tx = tx;
        this.clock = clock;
        this.props = props;
    }

    public void forgot(String email, String clientIp, boolean chinese) {
        limiter.check("forgot-ip", clientIp, 20, Duration.ofHours(1));
        String normalized = AuthService.normalize(email);
        Thread.ofVirtual().name("password-reset-mail").start(() -> {
            try {
                // per address, silently: a caller cannot use the limit to learn whether the account exists
                limiter.check("forgot-email", normalized, 3, Duration.ofHours(1));
            } catch (ApiException limited) {
                return;
            }
            var user = db.sql("select id, display_name from users where email = ?").param(normalized)
                    .query((rs, n) -> new Object[] {rs.getLong(1), rs.getString(2)}).optional();
            if (user.isEmpty()) {
                return;
            }
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            db.sql("insert into password_resets (token_hash, user_id, expires_at) values (?, ?, ?)")
                    .params(AuthService.sha256(token), user.get()[0], Timestamp.from(clock.instant().plus(LINK_TTL)))
                    .update();
            String link = props.publicOrigin() + "/reset#token=" + token;
            String name = (String) user.get()[1];
            mailer.send(normalized, chinese ? "重置你的 Wokflow 密码" : "Reset your Wokflow password", chinese
                    ? name + "，你好：\n\n请在 30 分钟内打开下面的链接设置新密码：\n" + link
                      + "\n\n如果不是你本人操作，请忽略这封邮件，你的密码不会改变。\n\n— Wokflow"
                    : "Hi " + name + ",\n\nUse this link within 30 minutes to choose a new password:\n" + link
                      + "\n\nIf you did not ask for this, ignore this email; your password has not changed.\n\n— Wokflow");
        });
    }

    public void reset(String token, String newPassword) {
        String hash = auth.hash(newPassword);
        Instant now = clock.instant();
        String email = tx.run(() -> {
            Long userId = db.sql("""
                    select user_id from password_resets where token_hash = ? and used_at is null and expires_at > ?
                    for update""")
                    .params(AuthService.sha256(token), Timestamp.from(now)).query(Long.class).optional().orElse(null);
            if (userId == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_reset_link", "This link has expired or was already used");
            }
            db.sql("update users set password_hash = ? where id = ?").params(hash, userId).update();
            db.sql("update password_resets set used_at = ? where user_id = ? and used_at is null")
                    .params(Timestamp.from(now), userId).update();
            refreshTokens.revokeAll(userId, now);
            return db.sql("select email from users where id = ?").param(userId).query(String.class).single();
        });
        limiter.clear("signin-fail", email, Duration.ofMinutes(10)); // a locked-out owner who proved control of the inbox can sign in again
    }

    /** Signed-in change: needs the current password, ends every other session. */
    public void change(CurrentUser u, String current, String next) {
        String stored = db.sql("select password_hash from users where id = ?").param(u.id()).query(String.class).single();
        limiter.check("password-change", Long.toString(u.id()), 10, Duration.ofHours(1));
        if (!auth.matches(current, stored)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "wrong_password", "Your current password is not correct");
        }
        String hash = auth.hash(next);
        tx.runVoid(() -> {
            db.sql("update users set password_hash = ? where id = ?").params(hash, u.id()).update();
            refreshTokens.revokeAll(u.id(), clock.instant());
        });
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT7M")
    public int purge() {
        return db.sql("delete from password_resets where expires_at < now() - interval '1 day'").update();
    }

    static boolean chinese(String acceptLanguage) {
        return acceptLanguage != null && acceptLanguage.toLowerCase(Locale.ROOT).startsWith("zh");
    }
}
