package com.insidejoke.billing;

import com.insidejoke.common.DbUtils;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EntitlementRepository {

    private static final RowMapper<EntitlementEntity> ROW_MAPPER = (rs, rowNum) -> map(rs);

    private static final String COLUMNS = "id, user_id, purchase_id, type, starts_at, ends_at, monthly_game_limit, "
            + "is_granted_by_admin, revoked_at, created_at, granted_by, revoked_by, revoke_reason";

    private final JdbcClient jdbc;

    public EntitlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A pass paid for by {@code purchaseId}. */
    public EntitlementEntity insert(
            UUID userId, UUID purchaseId, Product type, Instant startsAt, Instant endsAt, Instant now) {
        return insert(userId, purchaseId, type, startsAt, endsAt, null, now);
    }

    /** A pass an admin gave away; {@code grantedBy} is the admin. */
    public EntitlementEntity grant(
            UUID userId, Product type, Instant startsAt, Instant endsAt, UUID grantedBy, Instant now) {
        return insert(userId, null, type, startsAt, endsAt, grantedBy, now);
    }

    private EntitlementEntity insert(
            UUID userId,
            @Nullable UUID purchaseId,
            Product type,
            Instant startsAt,
            Instant endsAt,
            @Nullable UUID grantedBy,
            Instant now) {
        return jdbc.sql("INSERT INTO entitlement (user_id, purchase_id, type, starts_at, ends_at, monthly_game_limit, "
                        + "is_granted_by_admin, granted_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING "
                        + COLUMNS)
                .param(userId)
                .param(purchaseId)
                .param(type.name())
                .param(DbUtils.ts(startsAt))
                .param(DbUtils.ts(endsAt))
                .param(type.monthlyGameLimit())
                .param(purchaseId == null)
                .param(grantedBy)
                .param(DbUtils.ts(now))
                .query(ROW_MAPPER)
                .single();
    }

    /** Passes valid at {@code now}, the one ending last first. */
    public List<EntitlementEntity> findActive(UUID userId, Instant now) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entitlement WHERE user_id = ? AND revoked_at IS NULL "
                        + "AND starts_at <= ? AND ends_at > ? ORDER BY ends_at DESC")
                .params(userId, DbUtils.ts(now), DbUtils.ts(now))
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * When the not-revoked pass of this kind that runs longest ends, if it runs past {@code now}. Counts passes that
     * haven't started yet: a pass bought while another waits its turn goes after that one.
     */
    public Optional<Instant> findLastEnd(UUID userId, Product type, Instant now) {
        return jdbc.sql("SELECT ends_at FROM entitlement WHERE user_id = ? AND type = ? AND revoked_at IS NULL "
                        + "AND ends_at > ? ORDER BY ends_at DESC LIMIT 1")
                .params(userId, type.name(), DbUtils.ts(now))
                .query((rs, n) -> DbUtils.instant(rs, "ends_at"))
                .optional();
    }

    public Optional<EntitlementEntity> findByPurchase(UUID purchaseId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM entitlement WHERE purchase_id = ?")
                .param(purchaseId)
                .query(ROW_MAPPER)
                .optional();
    }

    public int revokeByPurchase(UUID purchaseId, Instant now, RevokeReason reason) {
        return jdbc.sql(
                        "UPDATE entitlement SET revoked_at = ?, revoke_reason = ? WHERE purchase_id = ? AND revoked_at IS NULL")
                .params(DbUtils.ts(now), reason.name(), purchaseId)
                .update();
    }

    /** Ends a pass now; {@code by} is the admin who did it, or null when it follows from something else. */
    public int revoke(UUID id, Instant now, RevokeReason reason, @Nullable UUID by) {
        return jdbc.sql(
                        "UPDATE entitlement SET revoked_at = ?, revoke_reason = ?, revoked_by = ? WHERE id = ? AND revoked_at IS NULL")
                .param(DbUtils.ts(now))
                .param(reason.name())
                .param(by)
                .param(id)
                .update();
    }

    static EntitlementEntity map(ResultSet rs) throws SQLException {
        String reason = rs.getString("revoke_reason");
        return new EntitlementEntity(
                DbUtils.uuid(rs, "id"),
                DbUtils.uuid(rs, "user_id"),
                DbUtils.uuid(rs, "purchase_id"),
                Product.valueOf(rs.getString("type")),
                DbUtils.instant(rs, "starts_at"),
                DbUtils.instant(rs, "ends_at"),
                DbUtils.integer(rs, "monthly_game_limit"),
                rs.getBoolean("is_granted_by_admin"),
                DbUtils.instant(rs, "revoked_at"),
                DbUtils.instant(rs, "created_at"),
                DbUtils.uuid(rs, "granted_by"),
                DbUtils.uuid(rs, "revoked_by"),
                reason == null ? null : RevokeReason.valueOf(reason));
    }

    /**
     * Serializes access decisions and pass purchases for one host until the surrounding transaction ends: two tabs
     * can't both start a free game, and two payments arriving together can't both start their pass at the same moment.
     */
    public void lockHost(UUID hostUserId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                .param("game-access:" + hostUserId)
                .query((rs, n) -> 1)
                .single();
    }
}
