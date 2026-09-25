package com.insidejoke.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.Party;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaddleWebhookIT extends AbstractIntegrationTest {

    private static final String SECRET = "pdl_ntfset_test_secret";
    private static final String PATH = "/api/webhooks/paddle";

    private ApiClient.Resp deliver(Map<String, Object> event) {
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        return client().postRaw(
                        PATH, body, "Paddle-Signature", PaddleSignatureUtils.sign(body, SECRET, clock.instant()));
    }

    private static Map<String, Object> completed(String eventId, String txnId, String priceId, Object userId) {
        return Map.of(
                "event_id",
                eventId,
                "event_type",
                "transaction.completed",
                "occurred_at",
                "2026-09-21T20:00:00Z",
                "data",
                Map.of(
                        "id",
                        txnId,
                        "status",
                        "completed",
                        "currency_code",
                        "EUR",
                        "custom_data",
                        Map.of("userId", String.valueOf(userId)),
                        "items",
                        List.of(Map.of("quantity", 1, "price", Map.of("id", priceId))),
                        "details",
                        Map.of("totals", Map.of("grand_total", "279"))));
    }

    /** A refund adjustment of transaction txn_c1. */
    private static Map<String, Object> refund(String eventId, String status, String type) {
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
                        "refund",
                        "status",
                        status,
                        "type",
                        type,
                        "transaction_id",
                        "txn_c1"));
    }

    /** An adjustment shaped like the ones Paddle sends: top-level type, items with their own type, totals in minor units. */
    private static Map<String, Object> adjustment(
            String eventId,
            String eventType,
            String txnId,
            String action,
            String status,
            String type,
            List<String> itemTypes,
            String total) {
        List<Map<String, Object>> items = itemTypes.stream()
                .map(t -> Map.<String, Object>of(
                        "id", "adjustment_item_" + t + eventId, "item_id", "transaction_item_1", "type", t))
                .toList();
        return Map.of(
                "event_id",
                eventId,
                "event_type",
                eventType,
                "data",
                Map.of(
                        "id",
                        "adj_" + txnId,
                        "action",
                        action,
                        "status",
                        status,
                        "type",
                        type,
                        "transaction_id",
                        txnId,
                        "items",
                        items,
                        "totals",
                        Map.of("total", total, "currency_code", "EUR")));
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    private UUID newHost() {
        return data.host().id();
    }

    @Test
    void aCompletedTransactionGrantsThePassOnce() {
        UUID host = newHost();
        assertThat(deliver(completed("evt_a1", "txn_a1", "pri_party", host)).status())
                .isEqualTo(200);
        assertThat(deliver(completed("evt_a1", "txn_a1", "pri_party", host)).status())
                .as("same event again")
                .isEqualTo(200);
        assertThat(deliver(completed("evt_a2", "txn_a1", "pri_party", host)).status())
                .as("same payment, new event")
                .isEqualTo(200);

        assertThat(count("SELECT count(*) FROM purchase WHERE user_id = ?", host))
                .isEqualTo(1);
        Map<String, Object> purchase = jdbc.sql("SELECT product, amount_minor, currency, status FROM purchase "
                        + "WHERE provider_txn_id = 'txn_a1'")
                .query()
                .singleRow();
        assertThat(purchase)
                .containsEntry("product", "PARTY_PASS")
                .containsEntry("amount_minor", 279)
                .containsEntry("status", "COMPLETED");
        assertThat(purchase.get("currency").toString().trim()).isEqualTo("EUR");
        Map<String, Object> pass = jdbc.sql(
                        "SELECT type, EXTRACT(EPOCH FROM ends_at - starts_at)::bigint AS len, monthly_game_limit "
                                + "FROM entitlement WHERE user_id = ?")
                .param(host)
                .query()
                .singleRow();
        assertThat(pass.get("type")).isEqualTo("PARTY_PASS");
        assertThat(pass.get("len")).isEqualTo(24L * 3600);
        assertThat(pass.get("monthly_game_limit")).isNull();
        assertThat(
                        count(
                                "SELECT count(*) FROM webhook_event WHERE event_id IN ('evt_a1','evt_a2') AND processed_at IS NOT NULL"))
                .isEqualTo(2);
    }

    @Test
    void hostPassHasAYearAndAMonthlyLimitAndPassesStack() {
        UUID host = newHost();
        deliver(completed("evt_b1", "txn_b1", "pri_host", host));
        Map<String, Object> pass = jdbc.sql(
                        "SELECT EXTRACT(EPOCH FROM ends_at - starts_at)::bigint AS len, monthly_game_limit "
                                + "FROM entitlement WHERE user_id = ?")
                .param(host)
                .query()
                .singleRow();
        assertThat(pass.get("len")).isEqualTo(365L * 24 * 3600);
        assertThat(pass.get("monthly_game_limit")).isEqualTo(15);

        deliver(completed("evt_b2", "txn_b2", "pri_party", host));
        deliver(completed("evt_b3", "txn_b3", "pri_party", host));
        List<Instant[]> parties = jdbc.sql(
                        "SELECT starts_at, ends_at FROM entitlement WHERE user_id = ? AND type = 'PARTY_PASS' "
                                + "ORDER BY starts_at")
                .param(host)
                .query((rs, n) -> new Instant[] {
                    rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant()
                })
                .list();
        assertThat(parties).hasSize(2);
        assertThat(parties.get(1)[0])
                .as("the second Party Pass starts when the first ends")
                .isEqualTo(parties.get(0)[1]);
    }

    @Test
    void anApprovedFullRefundRevokesThePass() {
        UUID host = newHost();
        deliver(completed("evt_c1", "txn_c1", "pri_party", host));
        deliver(refund("evt_c2", "pending_approval", "full"));
        deliver(refund("evt_c3", "approved", "partial"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .isEqualTo(1);

        deliver(refund("evt_c4", "approved", "full"));
        assertThat(count("SELECT count(*) FROM purchase WHERE provider_txn_id = 'txn_c1' AND status = 'REFUNDED'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NOT NULL", host))
                .isEqualTo(1);
        assertThat(deliver(refund("evt_c5", "approved", "full")).status()).isEqualTo(200);
    }

    @Test
    void badSignaturesAreRefusedAndNothingIsStored() {
        UUID host = newHost();
        byte[] body = json.writeValueAsString(completed("evt_d1", "txn_d1", "pri_party", host))
                .getBytes(StandardCharsets.UTF_8);
        assertThat(client().postRaw(PATH, body).status()).isEqualTo(401);
        assertThat(client().postRaw(
                                PATH,
                                body,
                                "Paddle-Signature",
                                PaddleSignatureUtils.sign(body, "wrong", clock.instant()))
                        .status())
                .isEqualTo(401);
        String stale = PaddleSignatureUtils.sign(body, SECRET, clock.instant().minus(Duration.ofMinutes(10)));
        assertThat(client().postRaw(PATH, body, "Paddle-Signature", stale).status())
                .isEqualTo(401);
        byte[] tampered = new String(body, StandardCharsets.UTF_8)
                .replace("pri_party", "pri_host")
                .getBytes(StandardCharsets.UTF_8);
        assertThat(client().postRaw(
                                PATH,
                                tampered,
                                "Paddle-Signature",
                                PaddleSignatureUtils.sign(body, SECRET, clock.instant()))
                        .status())
                .isEqualTo(401);
        assertThat(count("SELECT count(*) FROM webhook_event WHERE event_id = 'evt_d1'"))
                .isZero();
        assertThat(count("SELECT count(*) FROM purchase WHERE provider_txn_id = 'txn_d1'"))
                .isZero();
    }

    @Test
    void eventsThatCanNeverWorkAreKeptForAManualLook() {
        UUID host = newHost();
        assertThat(deliver(completed("evt_e1", "txn_e1", "pri_unknown", host)).status())
                .isEqualTo(200);
        assertThat(deliver(completed("evt_e2", "txn_e2", "pri_party", UUID.randomUUID()))
                        .status())
                .isEqualTo(200);
        assertThat(deliver(completed("evt_e3", "txn_e3", "pri_party", "not-a-uuid"))
                        .status())
                .isEqualTo(200);
        List<String> errors = jdbc.sql(
                        "SELECT last_error FROM webhook_event WHERE event_id IN ('evt_e1','evt_e2','evt_e3') "
                                + "AND processed_at IS NULL ORDER BY event_id")
                .query(String.class)
                .list();
        assertThat(errors).hasSize(3);
        assertThat(errors.getFirst()).contains("unknown price pri_unknown");
        assertThat(count("SELECT count(*) FROM purchase WHERE provider_txn_id IN ('txn_e1','txn_e2','txn_e3')"))
                .isZero();

        assertThat(deliver(Map.of("event_id", "evt_e4", "event_type", "customer.created", "data", Map.of()))
                        .status())
                .isEqualTo(200);
        assertThat(count("SELECT count(*) FROM webhook_event WHERE event_id = 'evt_e4' AND processed_at IS NOT NULL"))
                .isEqualTo(1);
        byte[] junk = "not json".getBytes(StandardCharsets.UTF_8);
        assertThat(client().postRaw(
                                PATH,
                                junk,
                                "Paddle-Signature",
                                PaddleSignatureUtils.sign(junk, SECRET, clock.instant()))
                        .status())
                .isEqualTo(400);
    }

    @Test
    void checkoutConfigIsForSignedInHostsOnly() {
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("pay"));
        JsonNode config = host.get("/api/billing/checkout").json();
        String id = host.get("/api/me").json().path("id").asString();
        assertThat(config.path("available").asBoolean()).isTrue();
        assertThat(config.path("environment").asString()).isEqualTo("sandbox");
        assertThat(config.path("clientToken").asString()).isEqualTo("test_client_token");
        assertThat(config.path("customData").path("userId").asString()).isEqualTo(id);
        assertThat(config.path("email").asString()).endsWith("@example.com");
        assertThat(config.path("offers").get(0).path("priceId").asString()).isEqualTo("pri_party");
        assertThat(config.path("offers").get(1).path("monthlyGameLimit").asInt())
                .isEqualTo(15);
        assertThat(client().get("/api/billing/checkout").status()).isEqualTo(401);
    }

    @Test
    void paywallThenPaymentThenTheGameStarts() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            UUID hostId =
                    UUID.fromString(party.host.get("/api/me").json().path("id").asString());
            data.game(hostId, null, clock.instant().minus(Duration.ofDays(1)));
            party.captain().getSocket().ok("game.start", Map.of());
            JsonNode walled = party.screen.state(s -> s.has("paywall"));
            assertThat(walled.path("paywall").asString()).isEqualTo("PAYWALL_FREE_LIMIT");
            assertThat(party.host
                            .get("/api/billing/passes")
                            .json()
                            .path("access")
                            .path("nextGame")
                            .asString())
                    .isEqualTo("PAYWALL");

            deliver(completed("evt_f1", "txn_f1", "pri_party", hostId));
            JsonNode access = party.host.get("/api/billing/passes").json().path("access");
            assertThat(access.path("nextGame").asString()).isEqualTo("PARTY_PASS");

            party.screen.ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            Await.until(
                    "paid session",
                    () -> count(
                                    "SELECT count(*) FROM game_session WHERE room_code = ? AND entitlement_id IS NOT NULL",
                                    party.code)
                            == 1);
        }
    }

    @Test
    void aFullRefundFromTheDashboardRevokesThePassOnceApproved() {
        UUID host = newHost();
        deliver(completed("evt_g1", "txn_g1", "pri_party", host));
        // Dashboard refunds arrive with the default top-level type "partial"; every item is "full". Sandbox approves
        // later.
        deliver(adjustment(
                "evt_g2",
                "adjustment.created",
                "txn_g1",
                "refund",
                "pending_approval",
                "partial",
                List.of("full"),
                "279"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .as("not approved yet")
                .isEqualTo(1);
        deliver(adjustment(
                "evt_g3", "adjustment.updated", "txn_g1", "refund", "approved", "partial", List.of("full"), "279"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .isZero();
        assertThat(count("SELECT count(*) FROM purchase WHERE provider_txn_id = 'txn_g1' AND status = 'REFUNDED'"))
                .isEqualTo(1);
    }

    @Test
    void aRefundCoveringThePaidAmountCountsAsFullAndLessDoesNot() {
        UUID host = newHost();
        deliver(completed("evt_h1", "txn_h1", "pri_party", host));
        deliver(adjustment(
                "evt_h2", "adjustment.updated", "txn_h1", "refund", "approved", "partial", List.of("partial"), "100"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .as("1.00 of 2.79")
                .isEqualTo(1);
        deliver(adjustment(
                "evt_h3", "adjustment.updated", "txn_h1", "refund", "approved", "partial", List.of("partial"), "279"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .isZero();
    }

    @Test
    void anApprovedChargebackRevokesThePass() {
        UUID host = newHost();
        deliver(completed("evt_i1", "txn_i1", "pri_host", host));
        deliver(adjustment(
                "evt_i2",
                "adjustment.created",
                "txn_i1",
                "chargeback_warning",
                "approved",
                "full",
                List.of("full"),
                "279"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .isEqualTo(1);
        deliver(adjustment(
                "evt_i3", "adjustment.created", "txn_i1", "chargeback", "approved", "full", List.of("full"), "279"));
        assertThat(count("SELECT count(*) FROM entitlement WHERE user_id = ? AND revoked_at IS NULL", host))
                .isZero();
    }
}
