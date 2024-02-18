package com.wokflow.api.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {
    public record Stored(UUID id, long userId, UUID familyId, Instant expiresAt, Instant rotatedAt, Instant revokedAt) {}

    private final JdbcClient db;

    public RefreshTokenRepository(JdbcClient db) { this.db = db; }

    public void insert(UUID id, long userId, UUID family, byte[] hash, Instant expiresAt) {
        db.sql("insert into refresh_tokens (id, user_id, family_id, token_hash, expires_at) values (?, ?, ?, ?, ?)")
                .params(id, userId, family, hash, java.sql.Timestamp.from(expiresAt)).update();
    }

    /** Locks the row so two concurrent refreshes with the same token serialize. */
    public Optional<Stored> lockByHash(byte[] hash) {
        return db.sql("""
                select id, user_id, family_id, expires_at, rotated_at, revoked_at
                from refresh_tokens where token_hash = ? for update""")
                .param(hash)
                .query((rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getLong("user_id"),
                        rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                        ts(rs.getTimestamp("rotated_at")), ts(rs.getTimestamp("revoked_at"))))
                .optional();
    }

    public void markRotated(UUID id, Instant at) {
        db.sql("update refresh_tokens set rotated_at = ? where id = ?").params(java.sql.Timestamp.from(at), id).update();
    }

    public int revokeFamily(UUID family, Instant at) {
        return db.sql("update refresh_tokens set revoked_at = ? where family_id = ? and revoked_at is null")
                .params(java.sql.Timestamp.from(at), family).update();
    }

    /** Ends every session of a user (password changed or reset). */
    public int revokeAll(long userId, Instant at) {
        return db.sql("update refresh_tokens set revoked_at = ? where user_id = ? and revoked_at is null")
                .params(java.sql.Timestamp.from(at), userId).update();
    }

    public int deleteExpired(Instant before) {
        return db.sql("delete from refresh_tokens where expires_at < ?").param(java.sql.Timestamp.from(before)).update();
    }

    private static Instant ts(java.sql.Timestamp t) { return t == null ? null : t.toInstant(); }
}
