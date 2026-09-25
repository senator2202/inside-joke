package com.insidejoke.billing;

import com.insidejoke.common.DbUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Raw provider events, stored before processing so retries are idempotent and failures can be replayed. */
@Repository
public class WebhookEventRepository {

    private final JdbcClient jdbc;

    public WebhookEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Stores the event; returns false if it had already been received. */
    public boolean insertIfAbsent(String provider, String eventId, String eventType, String payloadJson, Instant now) {
        return jdbc.sql("INSERT INTO webhook_event (provider, event_id, event_type, payload, received_at) "
                                + "VALUES (?, ?, ?, CAST(? AS jsonb), ?) ON CONFLICT (provider, event_id) DO NOTHING")
                        .params(provider, eventId, eventType, payloadJson, DbUtils.ts(now))
                        .update()
                == 1;
    }

    public boolean isProcessed(String provider, String eventId) {
        return jdbc.sql("SELECT processed_at IS NOT NULL FROM webhook_event WHERE provider = ? AND event_id = ?")
                .params(provider, eventId)
                .query(Boolean.class)
                .optional()
                .orElse(false);
    }

    /** What the server did with an event. */
    public enum Outcome {
        APPLIED,
        NOT_APPLIED,
        DUPLICATE,
        IGNORED,
        FAILED
    }

    /** Records that an event was handled, what came of it and, when something was not applied, why. */
    public void markProcessed(
            String provider,
            String eventId,
            Instant now,
            Outcome outcome,
            @Nullable String reason,
            @Nullable String detail) {
        jdbc.sql("UPDATE webhook_event SET processed_at = ?, last_error = NULL, outcome = ?, reason = ?, detail = ? "
                        + "WHERE provider = ? AND event_id = ?")
                .param(DbUtils.ts(now))
                .param(outcome.name())
                .param(reason)
                .param(clip(detail))
                .param(provider)
                .param(eventId)
                .update();
    }

    /** An event that arrived but was never processed, for the admin panel. */
    public record Pending(String provider, String eventId, String eventType, Instant receivedAt, String lastError) {}

    public List<Pending> listUnprocessed(int limit) {
        return jdbc.sql(
                        "SELECT provider, event_id, event_type, received_at, last_error FROM webhook_event WHERE processed_at IS NULL "
                                + "ORDER BY received_at DESC LIMIT ?")
                .param(limit)
                .query((rs, n) -> new Pending(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant(),
                        rs.getString(5)))
                .list();
    }

    public Optional<String> payload(String provider, String eventId) {
        return jdbc.sql("SELECT payload::text FROM webhook_event WHERE provider = ? AND event_id = ?")
                .params(provider, eventId)
                .query(String.class)
                .optional();
    }

    public void markFailed(String provider, String eventId, String error) {
        jdbc.sql("UPDATE webhook_event SET last_error = ?, outcome = " + DbUtils.sql(Outcome.FAILED)
                        + " WHERE provider = ? AND event_id = ?")
                .params(clip(error), provider, eventId)
                .update();
    }

    private static @Nullable String clip(@Nullable String text) {
        return text == null || text.length() <= 1000 ? text : text.substring(0, 1000);
    }

    // ---------------------------------------------------------------- refunds and chargebacks not applied

    /** A refund or chargeback the server received but did not apply, with the purchase and pass it concerns. */
    public record NotAppliedEvent(
            String eventId,
            String eventType,
            Instant receivedAt,
            String action,
            String status,
            String txnId,
            String reason,
            String detail,
            UUID purchaseId,
            String product,
            Integer amountMinor,
            String currency,
            String purchaseStatus,
            UUID userId,
            String userEmail,
            UUID passId,
            Instant passRevokedAt,
            Instant passEndsAt) {}

    private static final String NOT_APPLIED_FROM = " FROM webhook_event w "
            + "LEFT JOIN purchase p ON p.provider_txn_id = w.payload->'data'->>'transaction_id' "
            + "LEFT JOIN app_user u ON u.id = p.user_id LEFT JOIN entitlement e ON e.purchase_id = p.id WHERE w.outcome = "
            + DbUtils.sql(Outcome.NOT_APPLIED) + "";
    private static final String STILL_HELD = " AND e.id IS NOT NULL AND e.revoked_at IS NULL AND e.ends_at > ?";

    public long countNotApplied(boolean onlyStillHeld, Instant now) {
        List<Object> params = onlyStillHeld ? List.of(DbUtils.ts(now)) : List.of();
        return jdbc.sql("SELECT count(*)" + NOT_APPLIED_FROM + (onlyStillHeld ? STILL_HELD : ""))
                .params(params)
                .query(Long.class)
                .single();
    }

    /** Newest first; {@code onlyStillHeld} keeps the ones where the customer still has the pass. */
    public List<NotAppliedEvent> notApplied(boolean onlyStillHeld, int limit, long offset, Instant now) {
        List<Object> params = new ArrayList<>();
        if (onlyStillHeld) {
            params.add(DbUtils.ts(now));
        }
        params.add(limit);
        params.add(offset);
        return jdbc.sql("SELECT w.event_id, w.event_type, w.received_at, w.payload->'data'->>'action' AS action, "
                        + "w.payload->'data'->>'status' AS status, w.payload->'data'->>'transaction_id' AS txn_id, w.reason, w.detail, "
                        + "p.id AS purchase_id, p.product, p.amount_minor, p.currency, p.status AS "
                        + "purchase_status, u.id AS user_id, u.email, "
                        + "e.id AS pass_id, e.revoked_at, e.ends_at" + NOT_APPLIED_FROM
                        + (onlyStillHeld ? STILL_HELD : "")
                        + " ORDER BY w.received_at DESC, w.event_id LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> new NotAppliedEvent(
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        DbUtils.instant(rs, "received_at"),
                        rs.getString("action"),
                        rs.getString("status"),
                        rs.getString("txn_id"),
                        rs.getString("reason"),
                        rs.getString("detail"),
                        DbUtils.uuid(rs, "purchase_id"),
                        rs.getString("product"),
                        DbUtils.integer(rs, "amount_minor"),
                        rs.getString("currency") == null
                                ? null
                                : rs.getString("currency").trim(),
                        rs.getString("purchase_status"),
                        DbUtils.uuid(rs, "user_id"),
                        rs.getString("email"),
                        DbUtils.uuid(rs, "pass_id"),
                        DbUtils.instant(rs, "revoked_at"),
                        DbUtils.instant(rs, "ends_at")))
                .list();
    }
}
