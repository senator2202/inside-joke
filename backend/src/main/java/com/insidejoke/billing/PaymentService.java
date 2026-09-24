package com.insidejoke.billing;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.auth.UserRepository;
import com.insidejoke.common.Money;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Turns Paddle events into purchases and passes. Only two kinds matter (blueprint 11): a completed transaction grants
 * a pass, an approved full refund takes it away. Everything else is stored and ignored.
 */
@Service
public class PaymentService {

    /** The event can never be processed as sent (unknown price or user); retrying won't help. */
    public static class UnprocessableEventException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        public UnprocessableEventException(String message) {
            super(message);
        }
    }

    public enum Result {
        GRANTED,
        REFUNDED,
        ALREADY_DONE,
        IGNORED,
        NOT_APPLIED
    }

    /**
     * What handling an event came to. {@code reason} is a stable code for NOT_APPLIED results (shown and filtered in the
     * admin panel); {@code detail} explains it to a person.
     */
    public record Handled(Result result, String reason, String detail) {

        static Handled of(Result result, String detail) {
            return new Handled(result, null, detail);
        }

        static Handled notApplied(NotAppliedReason reason, String detail) {
            return new Handled(Result.NOT_APPLIED, reason.name(), detail);
        }

        public WebhookEventRepository.Outcome outcome() {
            return switch (result) {
                case GRANTED, REFUNDED -> WebhookEventRepository.Outcome.APPLIED;
                case ALREADY_DONE -> WebhookEventRepository.Outcome.DUPLICATE;
                case IGNORED -> WebhookEventRepository.Outcome.IGNORED;
                case NOT_APPLIED -> WebhookEventRepository.Outcome.NOT_APPLIED;
            };
        }
    }

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaddleProperties paddle;
    private final PurchaseRepository purchases;
    private final EntitlementRepository entitlements;
    private final AnalyticsService analytics;
    private final UserRepository users;
    private final Clock clock;

    public PaymentService(
            PaddleProperties paddle,
            PurchaseRepository purchases,
            EntitlementRepository entitlements,
            AnalyticsService analytics,
            UserRepository users,
            Clock clock) {
        this.paddle = paddle;
        this.purchases = purchases;
        this.entitlements = entitlements;
        this.analytics = analytics;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public Handled handle(String eventType, JsonNode data) {
        return switch (PaddleEventType.fromWire(eventType)) {
            case TRANSACTION_COMPLETED -> completed(data);
            case ADJUSTMENT_CREATED, ADJUSTMENT_UPDATED -> adjustment(data);
            case OTHER -> Handled.of(Result.IGNORED, "Not an event this app acts on.");
        };
    }

    private Handled completed(JsonNode txn) {
        String txnId = txn.path("id").asString("");
        if (txnId.isBlank()) {
            throw new UnprocessableEventException("transaction without id");
        }
        String priceId = txn.path("items").path(0).path("price").path("id").asString("");
        Product product = paddle.productFor(priceId)
                .orElseThrow(() -> new UnprocessableEventException("unknown price " + priceId));
        UUID userId = userOf(txn)
                .orElseThrow(() -> new UnprocessableEventException("transaction " + txnId + " has no known userId"));
        int amount = parseMinor(
                txn.path("details").path("totals").path("grand_total").asString("0"));
        String currency = txn.path("currency_code").asString("USD").toUpperCase();
        Instant now = clock.instant();

        Optional<PurchaseEntity> recorded = purchases.insertIfAbsent(userId, txnId, product, amount, currency, now);
        if (recorded.isEmpty()) {
            return Handled.of(Result.ALREADY_DONE, "This payment was already recorded.");
        }
        // A second pass of the same kind starts when the current one ends instead of overlapping it.
        Instant start = entitlements.findActive(userId, now).stream()
                .filter(e -> e.type() == product)
                .map(EntitlementEntity::endsAt)
                .max(Instant::compareTo)
                .filter(end -> end.isAfter(now))
                .orElse(now);
        EntitlementEntity pass =
                entitlements.insert(userId, recorded.get().id(), product, start, start.plus(product.validity()), now);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("product", product.name());
        props.put("amount_minor", amount);
        props.put("currency", currency);
        analytics.track("purchase_completed", userId.toString(), props);
        log.info("Granted {} {} until {} for purchase {}", product, pass.id(), pass.endsAt(), txnId);
        return Handled.of(Result.GRANTED, product + " granted until " + pass.endsAt() + ".");
    }

    /**
     * An approved refund or chargeback that covers the whole purchase takes the pass away. Paddle marks a whole refund
     * in more than one way: {@code type: "full"}, every item {@code type: "full"} (a refund made in the dashboard can
     * arrive with the default {@code type: "partial"} at the top), or a total that covers what was paid. A refund for less
     * than the purchase leaves the pass alone. Sandbox approves refunds in batches, so the pass goes when
     * {@code adjustment.updated} reports {@code approved}, which can take minutes.
     */
    private Handled adjustment(JsonNode adj) {
        String rawAction = adj.path("action").asString("");
        String rawStatus = adj.path("status").asString("");
        AdjustmentAction action = AdjustmentAction.fromWire(rawAction);
        AdjustmentStatus status = AdjustmentStatus.fromWire(rawStatus);
        String txnId = adj.path("transaction_id").asString("");
        switch (action) {
            case CHARGEBACK_WARNING -> {
                return Handled.notApplied(
                        NotAppliedReason.CHARGEBACK_WARNING,
                        "The customer's bank warned of a possible dispute. Nothing changes unless a chargeback follows.");
            }
            case CHARGEBACK_REVERSE -> {
                return Handled.notApplied(
                        NotAppliedReason.CHARGEBACK_REVERSED,
                        "The chargeback was reversed in your favour. The pass is not restored automatically: "
                                + "grant it again if the customer should keep playing.");
            }
            case REFUND, CHARGEBACK -> {
                // handled below
            }
            case CREDIT, CREDIT_REVERSE, OTHER -> {
                return Handled.of(Result.IGNORED, "A " + rawAction + " adjustment doesn't change passes.");
            }
        }
        String what = action == AdjustmentAction.REFUND ? "refund" : "chargeback";
        if (status != AdjustmentStatus.APPROVED) {
            log.info("Paddle {} on {} is {}: not applied yet", what, txnId, rawStatus);
            return switch (status) {
                case PENDING_APPROVAL ->
                    Handled.notApplied(
                            NotAppliedReason.AWAITING_APPROVAL,
                            "Paddle hasn't approved this " + what
                                    + " yet. The pass is taken away when an approval arrives (sandbox approves every ten minutes).");
                case REJECTED ->
                    Handled.notApplied(NotAppliedReason.REJECTED, "Paddle rejected this " + what + ". The pass stays.");
                default ->
                    Handled.notApplied(
                            NotAppliedReason.UNEXPECTED_STATUS,
                            "The " + what + " has status \"" + rawStatus + "\". Nothing was changed.");
            };
        }
        PurchaseEntity purchase = purchases
                .findByTxn(txnId)
                .orElseThrow(() -> new UnprocessableEventException(rawAction + " for unknown transaction " + txnId));
        if (purchase.status() == PurchaseStatus.REFUNDED) {
            return Handled.of(Result.ALREADY_DONE, "The purchase was already refunded.");
        }
        if (!coversWholePurchase(adj, purchase)) {
            log.info("Partial {} on {} recorded; the pass stays active", what, txnId);
            return Handled.notApplied(
                    NotAppliedReason.PARTIAL,
                    "Partial " + what + ": "
                            + Money.of(refundedMinor(adj), purchase.amount().currency())
                                    .format()
                            + " of " + purchase.amount().format()
                            + " paid. The pass stays active; revoke it by hand if the customer should lose it.");
        }
        RevokeReason kind = action == AdjustmentAction.REFUND ? RevokeReason.REFUND : RevokeReason.CHARGEBACK;
        Instant now = clock.instant();
        purchases.markRefunded(txnId, now, kind);
        entitlements.revokeByPurchase(purchase.id(), now, kind);
        analytics.track(
                "purchase_refunded",
                purchase.userId().toString(),
                Map.of("product", purchase.product().name(), "action", rawAction));
        log.info("Paddle {} on {}: pass revoked", rawAction, txnId);
        return Handled.of(Result.REFUNDED, "Pass revoked after an approved " + what + ".");
    }

    private static long refundedMinor(JsonNode adj) {
        try {
            return Long.parseLong(adj.path("totals").path("total").asString("0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 279 EUR -> "2.79 EUR"; currencies without minor units (JPY) stay whole. */
    private static boolean coversWholePurchase(JsonNode adj, PurchaseEntity purchase) {
        if ("full".equals(adj.path("type").asString(""))) {
            return true;
        }
        JsonNode items = adj.path("items");
        if (items.isArray() && !items.isEmpty()) {
            boolean allFull = true;
            for (JsonNode item : items) {
                allFull &= "full".equals(item.path("type").asString(""));
            }
            if (allFull) {
                return true;
            }
        }
        try {
            long refunded = Long.parseLong(
                    adj.path("totals").path("total").asString("0").trim());
            return refunded > 0
                    && Money.of(refunded, purchase.amount().currency()).isAtLeast(purchase.amount());
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Optional<UUID> userOf(JsonNode txn) {
        String raw = txn.path("custom_data").path("userId").asString("");
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean exists = users.exists(id);
        return exists ? Optional.of(id) : Optional.empty();
    }

    private static int parseMinor(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new UnprocessableEventException("bad amount " + value);
        }
    }
}
