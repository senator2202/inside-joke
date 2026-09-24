package com.insidejoke.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.Money;
import com.insidejoke.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the server records for each Paddle event: outcome, reason and a human explanation. */
class RefundOutcomesIT extends AbstractIntegrationTest {

    private static final String SECRET = "pdl_ntfset_test_secret";

    private void deliver(Map<String, Object> event) {
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        assertThat(client().postRaw(
                                "/api/webhooks/paddle",
                                body,
                                "Paddle-Signature",
                                PaddleSignatureUtils.sign(body, SECRET, clock.instant()))
                        .status())
                .isEqualTo(200);
    }

    private static Map<String, Object> completed(String eventId, String txnId, UUID user) {
        return Map.of(
                "event_id",
                eventId,
                "event_type",
                "transaction.completed",
                "data",
                Map.of(
                        "id",
                        txnId,
                        "status",
                        "completed",
                        "currency_code",
                        "EUR",
                        "custom_data",
                        Map.of("userId", user.toString()),
                        "items",
                        List.of(Map.of("quantity", 1, "price", Map.of("id", "pri_party"))),
                        "details",
                        Map.of("totals", Map.of("grand_total", "279"))));
    }

    private static Map<String, Object> adjustment(
            String eventId, String txnId, String action, String status, String total) {
        return Map.of(
                "event_id",
                eventId,
                "event_type",
                "adjustment.updated",
                "data",
                Map.of(
                        "id",
                        "adj_" + eventId,
                        "action",
                        action,
                        "status",
                        status,
                        "type",
                        "partial",
                        "transaction_id",
                        txnId,
                        "items",
                        List.of(Map.of("type", "partial")),
                        "totals",
                        Map.of("total", total, "currency_code", "EUR")));
    }

    private Map<String, Object> row(String eventId) {
        return jdbc.sql(
                        "SELECT outcome, reason, detail, processed_at IS NOT NULL AS processed FROM webhook_event WHERE event_id = ?")
                .param(eventId)
                .query()
                .singleRow();
    }

    @Test
    void everyEventRecordsWhatCameOfIt() {
        UUID host = data.host().id();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        deliver(completed("evt_o1_" + tag, "txn_o1_" + tag, host));
        assertThat(row("evt_o1_" + tag)).containsEntry("outcome", "APPLIED").containsEntry("processed", true);
        deliver(completed("evt_o2_" + tag, "txn_o1_" + tag, host));
        assertThat(row("evt_o2_" + tag)).containsEntry("outcome", "DUPLICATE");
        deliver(Map.of("event_id", "evt_o3_" + tag, "event_type", "customer.created", "data", Map.of()));
        assertThat(row("evt_o3_" + tag)).containsEntry("outcome", "IGNORED");
        deliver(Map.of(
                "event_id",
                "evt_o4_" + tag,
                "event_type",
                "transaction.completed",
                "data",
                Map.of("id", "txn_x_" + tag, "items", List.of(Map.of("price", Map.of("id", "pri_nope"))))));
        assertThat(row("evt_o4_" + tag)).containsEntry("outcome", "FAILED").containsEntry("processed", false);
    }

    @Test
    void refundsAndChargebacksThatAreNotAppliedSayWhy() {
        UUID host = data.host().id();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String txn = "txn_n_" + tag;
        deliver(completed("evt_n0_" + tag, txn, host));

        deliver(adjustment("evt_n1_" + tag, txn, "refund", "pending_approval", "279"));
        assertThat(row("evt_n1_" + tag))
                .containsEntry("outcome", "NOT_APPLIED")
                .containsEntry("reason", "AWAITING_APPROVAL");
        deliver(adjustment("evt_n2_" + tag, txn, "refund", "rejected", "279"));
        assertThat(row("evt_n2_" + tag)).containsEntry("reason", "REJECTED");
        deliver(adjustment("evt_n3_" + tag, txn, "refund", "approved", "100"));
        Map<String, Object> partial = row("evt_n3_" + tag);
        assertThat(partial).containsEntry("reason", "PARTIAL");
        assertThat(partial.get("detail").toString()).contains("1.00 EUR of 2.79 EUR");
        deliver(adjustment("evt_n4_" + tag, txn, "chargeback_warning", "approved", "279"));
        assertThat(row("evt_n4_" + tag)).containsEntry("reason", "CHARGEBACK_WARNING");
        deliver(adjustment("evt_n5_" + tag, txn, "credit", "approved", "279"));
        assertThat(row("evt_n5_" + tag)).containsEntry("outcome", "IGNORED");
        assertThat(jdbc.sql("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL")
                        .param(host)
                        .query(Integer.class)
                        .single())
                .as("none of these touch the pass")
                .isEqualTo(1);

        deliver(adjustment("evt_n6_" + tag, txn, "chargeback", "approved", "279"));
        assertThat(row("evt_n6_" + tag)).containsEntry("outcome", "APPLIED");
        Map<String, Object> purchase = jdbc.sql("SELECT refund_kind, refunded_at IS NOT NULL AS refunded FROM purchase "
                        + "WHERE provider_txn_id = ?")
                .param(txn)
                .query()
                .singleRow();
        assertThat(purchase).containsEntry("refund_kind", "CHARGEBACK").containsEntry("refunded", true);
        assertThat(jdbc.sql("SELECT revoke_reason FROM entitlement WHERE user_id = ?")
                        .param(host)
                        .query(String.class)
                        .single())
                .isEqualTo("CHARGEBACK");

        deliver(adjustment("evt_n7_" + tag, txn, "chargeback_reverse", "approved", "279"));
        Map<String, Object> reversed = row("evt_n7_" + tag);
        assertThat(reversed).containsEntry("reason", "CHARGEBACK_REVERSED");
        assertThat(reversed.get("detail").toString()).contains("not restored automatically");
    }

    @Test
    void currenciesWithoutCentsAreFormattedWhole() {
        assertThat(Money.of(500, "JPY").format()).isEqualTo("500 JPY");
        assertThat(Money.of(1499, "USD").format()).isEqualTo("14.99 USD");
    }
}
