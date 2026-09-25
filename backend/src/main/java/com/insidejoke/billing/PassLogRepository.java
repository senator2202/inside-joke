package com.insidejoke.billing;

import com.insidejoke.common.DbUtils;
import com.insidejoke.common.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The log of every pass, bought or granted, with its purchase, refund and audit trail (read model for the admin panel). */
@Repository
public class PassLogRepository {

    private static final RowMapper<Entry> ROW_MAPPER = (rs, rowNum) -> entry(rs);

    /** Filters; null means any. Dates are UTC days, both included; {@code email} is a case-insensitive fragment. */
    public record Filter(
            Product type, PassSource source, PassLogStatus status, LocalDate from, LocalDate to, String email) {}

    /** Sortable columns; the SQL never contains anything but these. */
    public enum Sort {
        CREATED("created_at"),
        ENDS("ends_at"),
        AMOUNT("amount_minor"),
        EMAIL("user_email");

        private final String column;

        Sort(String column) {
            this.column = column;
        }
    }

    public record Entry(
            UUID id,
            Product type,
            PassSource source,
            PassLogStatus status,
            UUID userId,
            String userEmail,
            boolean userDeleted,
            Instant createdAt,
            Instant startsAt,
            Instant endsAt,
            Integer monthlyGameLimit,
            int gamesPlayed,
            String txnId,
            Integer amountMinor,
            String currency,
            Instant refundedAt,
            String refundKind,
            String grantedByEmail,
            Instant revokedAt,
            String revokeReason,
            String revokedByEmail) {}

    public record TypeCounts(String type, long sold, long granted, long refunded, long chargebacks, long active) {}

    public record Revenue(Money gross, Money refunded) {}

    private final JdbcClient jdbc;

    public PassLogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Status of a pass at {@code now}: refunded beats revoked beats the time window. */
    private static final String STATUS_SQL = "CASE WHEN p.status = " + DbUtils.sql(PurchaseStatus.REFUNDED)
            + " THEN " + DbUtils.sql(PassLogStatus.REFUNDED)
            + " WHEN e.revoked_at IS NOT NULL THEN " + DbUtils.sql(PassLogStatus.REVOKED)
            + " WHEN e.starts_at > ? THEN " + DbUtils.sql(PassLogStatus.UPCOMING)
            + " WHEN e.ends_at <= ? THEN " + DbUtils.sql(PassLogStatus.EXPIRED)
            + " ELSE " + DbUtils.sql(PassLogStatus.ACTIVE) + " END";

    /** The filtered rows (without the status filter) as a CTE named {@code rows}; appends its parameters. */
    private static String rowsCte(Filter f, Instant now, List<Object> params) {
        StringBuilder sql = new StringBuilder(
                "WITH rows AS (SELECT e.id, e.type, e.purchase_id, e.user_id, u.email AS user_email, "
                        + "u.deleted_at IS NOT NULL AS user_deleted, e.created_at, e.starts_at, e.ends_at, e.monthly_game_limit, "
                        + "p.provider_txn_id, p.amount_minor, p.currency, p.refunded_at, p.refund_kind, g.email AS granted_by_email, "
                        + "e.revoked_at, e.revoke_reason, r.email AS revoked_by_email, " + STATUS_SQL + " AS status "
                        + "FROM entitlement e JOIN app_user u ON u.id = e.user_id LEFT JOIN purchase p ON p.id = e.purchase_id "
                        + "LEFT JOIN app_user g ON g.id = e.granted_by LEFT JOIN app_user r ON r.id = e.revoked_by WHERE TRUE");
        params.add(DbUtils.ts(now));
        params.add(DbUtils.ts(now));
        if (f.type() != null) {
            sql.append(" AND e.type = ?");
            params.add(f.type().name());
        }
        if (f.source() != null) {
            sql.append(
                    f.source() == PassSource.PURCHASE
                            ? " AND e.purchase_id IS NOT NULL"
                            : " AND e.purchase_id IS NULL");
        }
        if (f.from() != null) {
            sql.append(" AND e.created_at >= ?");
            params.add(DbUtils.ts(f.from().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.to() != null) {
            sql.append(" AND e.created_at < ?");
            params.add(
                    DbUtils.ts(f.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        if (f.email() != null) {
            sql.append(" AND u.email ILIKE ? ESCAPE '\\'");
            params.add("%"
                    + f.email()
                            .toLowerCase(Locale.ROOT)
                            .replace("\\", "\\\\")
                            .replace("%", "\\%")
                            .replace("_", "\\_") + "%");
        }
        return sql.append(")").toString();
    }

    /** The filtered rows as a CTE, then {@code select} from them with the status filter; fills {@code params}. */
    private String selectRows(Filter f, Instant now, String select, List<Object> params) {
        String cte = rowsCte(f, now, params);
        if (f.status() == null) {
            return cte + " SELECT " + select + " FROM rows";
        }
        params.add(f.status().name());
        return cte + " SELECT " + select + " FROM rows WHERE status = ?";
    }

    public long count(Filter f, Instant now) {
        List<Object> params = new ArrayList<>();
        return jdbc.sql(selectRows(f, now, "count(*)", params))
                .params(params)
                .query(Long.class)
                .single();
    }

    public List<Entry> list(Filter f, Sort sort, boolean ascending, int limit, long offset, Instant now) {
        List<Object> params = new ArrayList<>();
        String rows = selectRows(
                f,
                now,
                "rows.*, (SELECT count(*) FROM game_session gs WHERE gs.entitlement_id = rows.id) AS games_played",
                params);
        params.add(limit);
        params.add(offset);
        String direction = ascending ? "ASC" : "DESC";
        return jdbc.sql(rows + " ORDER BY " + sort.column + " " + direction
                        + " NULLS LAST, id " + direction
                        + " LIMIT ? OFFSET ?")
                .params(params)
                .query(ROW_MAPPER)
                .list();
    }

    /** Counts per pass type for the filters (the status filter doesn't apply: {@code active} counts the active ones). */
    public List<TypeCounts> countsByType(Filter f, Instant now) {
        List<Object> params = new ArrayList<>();
        return jdbc.sql(rowsCte(f, now, params)
                        + " SELECT type, count(*) FILTER (WHERE purchase_id IS NOT NULL) AS sold, "
                        + "count(*) FILTER (WHERE purchase_id IS NULL) AS granted, count(*) FILTER (WHERE "
                        + "refund_kind = " + DbUtils.sql(RevokeReason.REFUND) + ") AS refunded, "
                        + "count(*) FILTER (WHERE refund_kind = " + DbUtils.sql(RevokeReason.CHARGEBACK)
                        + ") AS chargebacks, count(*) FILTER "
                        + "(WHERE status = " + DbUtils.sql(PassLogStatus.ACTIVE) + ") AS active "
                        + "FROM rows GROUP BY type ORDER BY type")
                .params(params)
                .query((rs, n) -> new TypeCounts(
                        rs.getString("type"),
                        rs.getLong("sold"),
                        rs.getLong("granted"),
                        rs.getLong("refunded"),
                        rs.getLong("chargebacks"),
                        rs.getLong("active")))
                .list();
    }

    /** Money from purchases matching the filters, per currency, largest first. */
    public List<Revenue> revenue(Filter f, Instant now) {
        List<Object> params = new ArrayList<>();
        return jdbc.sql(rowsCte(f, now, params) + " SELECT currency, sum(amount_minor) AS gross, "
                        + "coalesce(sum(amount_minor) FILTER (WHERE refunded_at IS NOT NULL), 0) AS refunded FROM rows "
                        + "WHERE purchase_id IS NOT NULL GROUP BY currency ORDER BY sum(amount_minor) DESC")
                .params(params)
                .query((rs, n) -> new Revenue(
                        Money.of(rs.getLong("gross"), rs.getString("currency")),
                        Money.of(rs.getLong("refunded"), rs.getString("currency"))))
                .list();
    }

    private static Entry entry(ResultSet rs) throws SQLException {
        return new Entry(
                DbUtils.uuid(rs, "id"),
                Product.valueOf(rs.getString("type")),
                rs.getObject("purchase_id") == null ? PassSource.GRANT : PassSource.PURCHASE,
                PassLogStatus.valueOf(rs.getString("status")),
                DbUtils.uuid(rs, "user_id"),
                rs.getString("user_email"),
                rs.getBoolean("user_deleted"),
                DbUtils.instant(rs, "created_at"),
                DbUtils.instant(rs, "starts_at"),
                DbUtils.instant(rs, "ends_at"),
                DbUtils.integer(rs, "monthly_game_limit"),
                rs.getInt("games_played"),
                rs.getString("provider_txn_id"),
                DbUtils.integer(rs, "amount_minor"),
                trim(rs.getString("currency")),
                DbUtils.instant(rs, "refunded_at"),
                rs.getString("refund_kind"),
                rs.getString("granted_by_email"),
                DbUtils.instant(rs, "revoked_at"),
                rs.getString("revoke_reason"),
                rs.getString("revoked_by_email"));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
