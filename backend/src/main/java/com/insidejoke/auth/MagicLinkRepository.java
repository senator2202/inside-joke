package com.insidejoke.auth;

import com.insidejoke.common.DbUtils;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class MagicLinkRepository {

    /** Pending sign-in challenge. Only hashes are stored, never the token or the code. */
    public record Challenge(byte[] tokenHash, String email, byte[] codeHash, int attempts, Instant expiresAt) {}

    private final JdbcClient jdbc;

    public MagicLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(byte[] tokenHash, String email, byte[] codeHash, Instant expiresAt, Instant now, String ip) {
        jdbc.sql("INSERT INTO magic_link_token (token_hash, email, code_hash, expires_at, created_at, created_ip) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS inet))")
                .params(tokenHash, email, codeHash, DbUtils.ts(expiresAt), DbUtils.ts(now), ip)
                .update();
    }

    /** Invalidates older unused challenges when a new code is requested. */
    public void supersede(String email, Instant now) {
        jdbc.sql("UPDATE magic_link_token SET used_at = ? WHERE lower(email) = lower(?) AND used_at IS NULL")
                .params(DbUtils.ts(now), email)
                .update();
    }

    public Optional<Challenge> findLatestUnused(String email) {
        return jdbc.sql("SELECT token_hash, email, code_hash, attempts, expires_at FROM magic_link_token "
                        + "WHERE lower(email) = lower(?) AND used_at IS NULL ORDER BY created_at DESC LIMIT 1")
                .param(email)
                .query((rs, n) -> new Challenge(
                        rs.getBytes("token_hash"),
                        rs.getString("email"),
                        rs.getBytes("code_hash"),
                        rs.getInt("attempts"),
                        DbUtils.instant(rs, "expires_at")))
                .optional();
    }

    public Optional<Challenge> findUnusedByToken(byte[] tokenHash) {
        return jdbc.sql("SELECT token_hash, email, code_hash, attempts, expires_at FROM magic_link_token "
                        + "WHERE token_hash = ? AND used_at IS NULL")
                .param(tokenHash)
                .query((rs, n) -> new Challenge(
                        rs.getBytes("token_hash"),
                        rs.getString("email"),
                        rs.getBytes("code_hash"),
                        rs.getInt("attempts"),
                        DbUtils.instant(rs, "expires_at")))
                .optional();
    }

    /** Atomically counts a wrong code; returns the new attempt count. */
    public int incrementAttempts(byte[] tokenHash) {
        return jdbc.sql("UPDATE magic_link_token SET attempts = attempts + 1 WHERE token_hash = ? RETURNING attempts")
                .param(tokenHash)
                .query(Integer.class)
                .single();
    }

    /** Marks the challenge used; false if another request consumed it first. */
    public boolean consume(byte[] tokenHash, Instant now) {
        return jdbc.sql("UPDATE magic_link_token SET used_at = ? WHERE token_hash = ? AND used_at IS NULL")
                        .params(DbUtils.ts(now), tokenHash)
                        .update()
                == 1;
    }

    public int deleteOlderThan(Instant cutoff) {
        return jdbc.sql("DELETE FROM magic_link_token WHERE created_at < ?")
                .param(DbUtils.ts(cutoff))
                .update();
    }
}
