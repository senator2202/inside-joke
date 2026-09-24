package com.insidejoke.billing;

import com.insidejoke.common.DbUtils;
import com.insidejoke.common.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PurchaseRepository {

    private static final String COLUMNS =
            "id, user_id, provider_txn_id, product, amount_minor, currency, status, created_at, updated_at";

    private final JdbcClient jdbc;

    public PurchaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a completed purchase once per provider transaction; returns empty if it was already recorded. */
    public Optional<PurchaseEntity> insertIfAbsent(
            UUID userId, String txnId, Product product, int amountMinor, String currency, Instant now) {
        return jdbc.sql(
                        "INSERT INTO purchase (user_id, provider_txn_id, product, amount_minor, currency, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, " + DbUtils.sql(PurchaseStatus.COMPLETED)
                                + ", ?, ?) ON CONFLICT (provider_txn_id) DO NOTHING RETURNING "
                                + COLUMNS)
                .params(userId, txnId, product.name(), amountMinor, currency, DbUtils.ts(now), DbUtils.ts(now))
                .query(PurchaseRepository::map)
                .optional();
    }

    public Optional<PurchaseEntity> findByTxn(String txnId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM purchase WHERE provider_txn_id = ?")
                .param(txnId)
                .query(PurchaseRepository::map)
                .optional();
    }

    /** Marks a purchase refunded (by a refund or a chargeback); returns the purchase if its status changed. */
    public Optional<PurchaseEntity> markRefunded(String txnId, Instant now, RevokeReason kind) {
        if (kind != RevokeReason.REFUND && kind != RevokeReason.CHARGEBACK) {
            throw new IllegalArgumentException("A purchase is refunded by a refund or a chargeback, not " + kind);
        }
        return jdbc.sql("UPDATE purchase SET status = " + DbUtils.sql(PurchaseStatus.REFUNDED)
                        + ", updated_at = ?, refunded_at = ?, refund_kind = ? "
                        + "WHERE provider_txn_id = ? AND status <> " + DbUtils.sql(PurchaseStatus.REFUNDED)
                        + " RETURNING " + COLUMNS)
                .params(DbUtils.ts(now), DbUtils.ts(now), kind.name(), txnId)
                .query(PurchaseRepository::map)
                .optional();
    }

    public List<PurchaseEntity> listByUser(UUID userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM purchase WHERE user_id = ? ORDER BY created_at DESC")
                .param(userId)
                .query(PurchaseRepository::map)
                .list();
    }

    static PurchaseEntity map(ResultSet rs, int row) throws SQLException {
        return new PurchaseEntity(
                DbUtils.uuid(rs, "id"),
                DbUtils.uuid(rs, "user_id"),
                rs.getString("provider_txn_id"),
                Product.valueOf(rs.getString("product")),
                Money.of(rs.getInt("amount_minor"), rs.getString("currency")),
                PurchaseStatus.valueOf(rs.getString("status")),
                DbUtils.instant(rs, "created_at"),
                DbUtils.instant(rs, "updated_at"));
    }

    // ---------------------------------------------------------------- reporting

    /** Completed purchases in [from, to), summed per currency, largest first. */
    public List<Money> revenue(Instant from, Instant to) {
        return jdbc.sql("SELECT currency, sum(amount_minor) FROM purchase WHERE status = "
                        + DbUtils.sql(PurchaseStatus.COMPLETED) + " "
                        + "AND created_at >= ? AND created_at < ? GROUP BY currency ORDER BY 2 DESC")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> Money.of(rs.getLong(2), rs.getString(1)))
                .list();
    }

    public Map<String, Integer> countByProduct(Instant from, Instant to) {
        Map<String, Integer> byProduct = new LinkedHashMap<>();
        jdbc.sql(
                        "SELECT product, count(*) FROM purchase WHERE created_at >= ? AND created_at < ? GROUP BY product ORDER BY product")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query((rs, n) -> byProduct.put(rs.getString(1), rs.getInt(2)))
                .list();
        return byProduct;
    }

    /** Purchases refunded (or charged back) in [from, to). */
    public int refunds(Instant from, Instant to) {
        return jdbc.sql("SELECT count(*) FROM purchase WHERE status = " + DbUtils.sql(PurchaseStatus.REFUNDED)
                        + " AND updated_at >= ? AND updated_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query(Integer.class)
                .single();
    }

    public int payingUsers(Instant from, Instant to) {
        return jdbc.sql("SELECT count(DISTINCT user_id) FROM purchase WHERE status = "
                        + DbUtils.sql(PurchaseStatus.COMPLETED) + " AND created_at >= ? AND created_at < ?")
                .params(DbUtils.ts(from), DbUtils.ts(to))
                .query(Integer.class)
                .single();
    }
}
