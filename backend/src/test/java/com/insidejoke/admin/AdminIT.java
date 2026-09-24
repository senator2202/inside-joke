package com.insidejoke.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.billing.EntitlementEntity;
import com.insidejoke.billing.PaddleSignatureUtils;
import com.insidejoke.billing.Product;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.Party;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminIT extends AbstractIntegrationTest {

    private ApiClient admin() {
        return Party.signIn(port, json, FAKE, "boss@example.com");
    }

    private static Timestamp ts(String iso) {
        return Timestamp.from(Instant.parse(iso));
    }

    private void aiCall(UUID game, String purpose, long cost, String outcome, boolean free, String at) {
        jdbc.sql(
                        "INSERT INTO ai_call (game_session_id, purpose, provider, model, cost_micros, latency_ms, outcome, is_free, "
                                + "created_at) VALUES (?, ?, 'test', 'm', ?, 100, ?, ?, ?)")
                .params(game, purpose, cost, outcome, free, ts(at))
                .update();
    }

    private void purchase(
            UUID user, String product, int amount, String currency, String status, String created, String updated) {
        jdbc.sql(
                        "INSERT INTO purchase (user_id, provider_txn_id, product, amount_minor, currency, status, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(user, "txn_" + UUID.randomUUID(), product, amount, currency, status, ts(created), ts(updated))
                .update();
    }

    @Test
    void onlyAdminsGetIn() {
        ApiClient host = Party.signIn(port, json, FAKE, uniqueEmail("nope"));
        List<String> paths = List.of(
                "/api/admin/metrics", "/api/admin/users?email=ex", "/api/admin/settings", "/api/admin/webhooks/failed");
        for (String path : paths) {
            assertThat(host.get(path).status()).as(path).isEqualTo(403);
            assertThat(client().get(path).status()).as(path).isEqualTo(401);
        }
        assertThat(host.post("/api/admin/drain", Map.of("enabled", true)).status())
                .isEqualTo(403);
        assertThat(admin().get("/api/me").json().path("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void metricsAddUpGamesMoneyAiAndConversionForTheRange() {
        UUID h1 = data.host().id();
        UUID h2 = data.host().id();
        UUID h3 = data.host().id();
        jdbc.sql("UPDATE app_user SET created_at = ? WHERE id IN (?, ?)")
                .params(ts("2031-03-02T09:00:00Z"), h1, h2)
                .update();
        EntitlementEntity pass = data.pass(h2, Product.PARTY_PASS);
        UUID g1 = data.game(h1, null, Instant.parse("2031-03-02T12:00:00Z"));
        UUID g2 = data.game(h1, null, Instant.parse("2031-03-05T20:00:00Z"));
        UUID g3 = data.game(h2, pass, Instant.parse("2031-03-05T21:00:00Z"));
        UUID outside = data.game(h3, null, Instant.parse("2031-03-09T10:00:00Z"));
        jdbc.sql("UPDATE game_session SET player_count = 4, end_reason = 'COMPLETED' WHERE id = ?")
                .param(g1)
                .update();
        jdbc.sql("UPDATE game_session SET player_count = 6 WHERE id = ?")
                .param(g2)
                .update();
        jdbc.sql("UPDATE game_session SET player_count = 5, end_reason = 'ENDED_BY_HOST' WHERE id = ?")
                .param(g3)
                .update();
        purchase(h1, "HOST_PASS", 1499, "USD", "COMPLETED", "2031-03-02T12:30:00Z", "2031-03-02T12:30:00Z");
        purchase(h2, "PARTY_PASS", 279, "EUR", "COMPLETED", "2031-03-05T20:55:00Z", "2031-03-05T20:55:00Z");
        purchase(h1, "PARTY_PASS", 299, "USD", "REFUNDED", "2031-03-02T13:00:00Z", "2031-03-05T09:00:00Z");
        aiCall(g1, "ROUND_GEN", 1000, "OK", true, "2031-03-02T12:01:00Z");
        aiCall(g1, "TTS", 500, "OK", true, "2031-03-02T12:02:00Z");
        aiCall(g3, "FINALE", 2000, "ERROR", false, "2031-03-05T21:30:00Z");
        aiCall(outside, "FINALE", 9999, "OK", true, "2031-03-09T10:05:00Z");

        JsonNode m =
                admin().get("/api/admin/metrics?from=2031-03-01&to=2031-03-07").json();
        JsonNode games = m.path("games");
        assertThat(games.path("total").asInt()).isEqualTo(3);
        assertThat(games.path("free").asInt()).isEqualTo(2);
        assertThat(games.path("paid").asInt()).isEqualTo(1);
        assertThat(games.path("completed").asInt()).isEqualTo(1);
        assertThat(games.path("avgPlayers").asDouble()).isEqualTo(5.0);
        assertThat(games.path("endReasons").path("IN_PROGRESS").asInt()).isEqualTo(1);

        JsonNode money = m.path("money");
        assertThat(money.path("revenue").get(0).path("currency").asString()).isEqualTo("USD");
        assertThat(money.path("revenue").get(0).path("amountMinor").asLong()).isEqualTo(1499);
        assertThat(money.path("revenue").get(1).path("amountMinor").asLong()).isEqualTo(279);
        assertThat(money.path("purchases").asInt()).isEqualTo(3);
        assertThat(money.path("byProduct").path("PARTY_PASS").asInt()).isEqualTo(2);
        assertThat(money.path("refunds").asInt()).isEqualTo(1);

        JsonNode ai = m.path("ai");
        assertThat(ai.path("costMicros").asLong()).isEqualTo(3500);
        assertThat(ai.path("freeGameCostMicros").asLong()).isEqualTo(1500);
        assertThat(ai.path("costPerGameMicros").asLong()).isEqualTo(1166);
        assertThat(ai.path("failures").asInt()).isEqualTo(1);
        assertThat(ai.path("costByPurpose").path("FINALE").asLong()).isEqualTo(2000);

        JsonNode funnel = m.path("funnel");
        assertThat(funnel.path("newHosts").asInt()).isEqualTo(2);
        assertThat(funnel.path("activeHosts").asInt()).isEqualTo(2);
        assertThat(funnel.path("payingHosts").asInt()).isEqualTo(2);
        assertThat(funnel.path("conversion").asDouble()).isEqualTo(1.0);

        JsonNode days = m.path("days");
        assertThat(days.size()).isEqualTo(7);
        assertThat(days.get(1).path("date").asString()).isEqualTo("2031-03-02");
        assertThat(days.get(1).path("aiCostMicros").asLong()).isEqualTo(1500);
        assertThat(days.get(4).path("games").asInt()).isEqualTo(2);
        assertThat(days.get(4).path("paidGames").asInt()).isEqualTo(1);
        assertThat(m.path("live").path("rooms").isInt()).isTrue();

        assertThat(admin().get("/api/admin/metrics?from=2031-03-07&to=2031-03-01")
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin().get("/api/admin/metrics?from=2029-01-01&to=2031-03-01")
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin().get("/api/admin/metrics").json().path("days").size())
                .as("last 30 days by default")
                .isEqualTo(30);
    }

    @Test
    void searchUsersGrantAndRevokePasses() {
        ApiClient admin = admin();
        String tag = "streamer-" + UUID.randomUUID().toString().substring(0, 6);
        ApiClient streamer = Party.signIn(port, json, FAKE, tag + "@example.com");
        UUID id = UUID.fromString(streamer.get("/api/me").json().path("id").asString());

        assertThat(admin.get("/api/admin/users?email=a").errorCode()).isEqualTo("VALIDATION_FAILED");
        JsonNode found = admin.get("/api/admin/users?email=" + tag).json();
        assertThat(found.size()).isEqualTo(1);
        assertThat(found.get(0).path("id").asString()).isEqualTo(id.toString());
        assertThat(found.get(0).path("passes").size()).isZero();

        String path = "/api/admin/users/" + id + "/passes";
        assertThat(admin.post(path, Map.of("type", "GOLD")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post(path, Map.of("type", "HOST_PASS", "days", 0)).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.post("/api/admin/users/" + UUID.randomUUID() + "/passes", Map.of("type", "HOST_PASS"))
                        .errorCode())
                .isEqualTo("NOT_FOUND");
        JsonNode granted =
                admin.post(path, Map.of("type", "HOST_PASS", "days", 30)).json();
        JsonNode pass = granted.path("passes").get(0);
        assertThat(pass.path("type").asString()).isEqualTo("HOST_PASS");
        assertThat(pass.path("grantedByAdmin").asBoolean()).isTrue();
        assertThat(pass.path("monthlyGameLimit").asInt()).isEqualTo(15);
        assertThat(streamer.get("/api/billing/passes")
                        .json()
                        .path("access")
                        .path("nextGame")
                        .asString())
                .isEqualTo("HOST_PASS");
        assertThat(jdbc.sql("SELECT EXTRACT(EPOCH FROM ends_at - starts_at)::bigint FROM entitlement WHERE user_id = ?")
                        .param(id)
                        .query(Long.class)
                        .single())
                .isEqualTo(30L * 86_400);

        String passId = pass.path("id").asString();
        assertThat(admin.post("/api/admin/passes/" + passId + "/revoke", Map.of())
                        .status())
                .isEqualTo(200);
        assertThat(admin.post("/api/admin/passes/" + passId + "/revoke", Map.of())
                        .errorCode())
                .isEqualTo("NOT_FOUND");
        assertThat(streamer.get("/api/billing/passes")
                        .json()
                        .path("access")
                        .path("passes")
                        .size())
                .isZero();
    }

    @Test
    void flagsLimitsAndDrainMode() {
        ApiClient admin = admin();
        assertThat(admin.get("/api/admin/settings").json().path("audience_cap").asInt())
                .isPositive();
        assertThat(admin.put("/api/admin/settings/audience_cap", Map.of("value", 500))
                        .json()
                        .path("audience_cap")
                        .asInt())
                .isEqualTo(500);
        assertThat(admin.put("/api/admin/settings/audience_cap", Map.of("value", "lots"))
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.put("/api/admin/settings/audience_cap", Map.of("value", -1))
                        .errorCode())
                .isEqualTo("VALIDATION_FAILED");
        assertThat(admin.put("/api/admin/settings/free_games_enabled", Map.of("value", false))
                        .json()
                        .path("free_games_enabled")
                        .asBoolean())
                .isFalse();
        assertThat(admin.put("/api/admin/settings/unknown", Map.of("value", true))
                        .errorCode())
                .isEqualTo("NOT_FOUND");

        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 0)) {
            JsonNode drained =
                    admin.post("/api/admin/drain", Map.of("enabled", true)).json();
            assertThat(drained.path("drainMode").asBoolean()).isTrue();
            assertThat(drained.path("live").path("rooms").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(client().get("/api/status").json().path("drainMode").asBoolean())
                    .isTrue();
            assertThat(party.host
                            .post("/api/rooms", Map.of("tone", "FAMILY", "length", "SHORT", "mode", "STANDARD"))
                            .errorCode())
                    .isEqualTo("DRAIN_MODE");
            assertThat(party.host.get("/api/rooms/" + party.code).status())
                    .as("running rooms keep going")
                    .isEqualTo(200);
            assertThat(admin.post("/api/admin/drain", Map.of("enabled", false))
                            .json()
                            .path("drainMode")
                            .asBoolean())
                    .isFalse();
        }
    }

    @Test
    void stuckWebhooksCanBeInspectedAndReplayed() {
        ApiClient admin = admin();
        UUID laterUser = UUID.randomUUID();
        Map<String, Object> event = Map.of(
                "event_id",
                "evt_admin_1",
                "event_type",
                "transaction.completed",
                "data",
                Map.of(
                        "id",
                        "txn_admin_1",
                        "status",
                        "completed",
                        "currency_code",
                        "USD",
                        "custom_data",
                        Map.of("userId", laterUser.toString()),
                        "items",
                        List.of(Map.of("quantity", 1, "price", Map.of("id", "pri_party"))),
                        "details",
                        Map.of("totals", Map.of("grand_total", "299"))));
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        client().postRaw(
                        "/api/webhooks/paddle",
                        body,
                        "Paddle-Signature",
                        PaddleSignatureUtils.sign(body, "pdl_ntfset_test_secret", clock.instant()));

        JsonNode failed = admin.get("/api/admin/webhooks/failed").json();
        JsonNode mine = null;
        for (JsonNode f : failed) {
            if (f.path("eventId").asString().equals("evt_admin_1")) {
                mine = f;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.path("lastError").asString()).contains("no known userId");

        // The account turns up later (for example, restored by support); replaying the stored event grants the pass.
        jdbc.sql("INSERT INTO app_user (id, email) VALUES (?, ?)")
                .params(laterUser, laterUser + "@example.com")
                .update();
        JsonNode replayed =
                admin.post("/api/admin/webhooks/evt_admin_1/replay", Map.of()).json();
        assertThat(replayed.path("processed").asBoolean()).isTrue();
        assertThat(replayed.path("result").asString()).isEqualTo("GRANTED");
        assertThat(jdbc.sql("SELECT count(*) FROM entitlement WHERE user_id = ?")
                        .param(laterUser)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
        assertThat(admin.get("/api/admin/webhooks/failed").json().toString()).doesNotContain("evt_admin_1");
        assertThat(admin.post("/api/admin/webhooks/evt_nope/replay", Map.of()).errorCode())
                .isEqualTo("NOT_FOUND");
    }
}
