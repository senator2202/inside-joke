package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.billing.Product;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Await;
import com.insidejoke.support.Driver;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.Party;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class GameFlowIT extends AbstractIntegrationTest {

    @Autowired
    RoomRegistryService registry;

    private static final String SECRET = "SECRET-XYZZY hides pineapple pizza in the freezer";

    @Test
    void fullShortGameWithAiHost() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone first = party.phones.getFirst();
            Party.Phone second = party.phones.get(1);
            second.getSocket().ok("dossier.add", Map.of("aboutPlayerId", first.id(), "text", SECRET));
            assertThat(party.screen
                            .state(s -> s.path("secrets").asInt() == 1)
                            .path("secrets")
                            .asInt())
                    .isEqualTo(1);

            party.captain().getSocket().ok("game.start", Map.of());
            Driver driver = new Driver(party, List.of("WHO_OF_US", "TRUTH_OR_AI", "ANSWER_DUEL", "WHO_OF_US"));
            driver.onStep(GameFlowIT::screenKeepsTheSecretsOfTheStep);
            JsonNode finale = driver.playToFinale();

            List<String> kinds = driver.steps().stream()
                    .filter(s -> "VOTING".equals(s.path("phase").asString()))
                    .map(s -> s.path("round").path("n").asInt() + ":"
                            + s.path("round").path("kind").asString())
                    .distinct()
                    .toList();
            assertThat(kinds).contains("1:ANSWER_DUEL", "2:WHO_OF_US", "3:TRUTH_OR_AI", "4:ANSWER_DUEL", "5:WHO_OF_US");
            assertThat(driver.steps())
                    .anyMatch(s -> s.path("round").path("prompt").asString("").startsWith("AI prompt"));
            assertThat(driver.steps())
                    .anyMatch(s -> List.of("TRUE STATEMENT from the dossier", "FAKE STATEMENT invented by the AI")
                            .contains(s.path("round").path("statement").asString("")));

            assertThat(finale.path("finale").path("speech").asString()).isEqualTo("AI closing speech.");
            assertThat(finale.path("finale").path("titles").path(first.id()).asString())
                    .isEqualTo("AI title for Player1");
            assertThat(finale.path("finale").path("winnerIds").size()).isPositive();
            assertThat(finale.path("finale").path("standings").size()).isEqualTo(3);
            assertThat(finale.path("finale").path("answerOfNight").asString()).contains("says:");
            assertThat(first.getSocket()
                            .state(s -> "FINALE".equals(s.path("phase").asString()))
                            .path("you")
                            .path("title")
                            .asString())
                    .isEqualTo("AI title for Player1");

            for (Party.Phone p : party.phones) {
                assertThat(String.join("\n", p.getSocket().rawFrames()))
                        .as("the dossier never reaches clients")
                        .doesNotContain("SECRET-XYZZY");
            }
            assertThat(String.join("\n", party.screen.rawFrames())).doesNotContain("SECRET-XYZZY");
            assertThat(FAKE.requests("POST", "/anthropic/v1/messages"))
                    .anyMatch(r -> r.body().contains("SECRET-XYZZY"));
            assertThat(FAKE.requests("POST", "/anthropic/v1/messages"))
                    .allMatch(r -> "test-anthropic-key".equals(r.header("x-api-key")));

            String sessionId = finale.path("sessionId").asString();
            Await.until(
                    "session finished",
                    () -> jdbc.sql("SELECT count(*) FROM game_session WHERE id = ?::uuid AND ended_at IS NOT NULL")
                                    .param(sessionId)
                                    .query(Integer.class)
                                    .single()
                            == 1);
            Map<String, Object> row = jdbc.sql(
                            "SELECT end_reason, rounds_played, player_count, dossier_facts, is_free FROM game_session "
                                    + "WHERE id = ?::uuid")
                    .param(sessionId)
                    .query()
                    .singleRow();
            assertThat(row)
                    .containsEntry("end_reason", "COMPLETED")
                    .containsEntry("rounds_played", 5)
                    .containsEntry("player_count", 3)
                    .containsEntry("dossier_facts", 1)
                    .containsEntry("is_free", true);
            List<String> purposes = jdbc.sql(
                            "SELECT DISTINCT purpose FROM ai_call WHERE game_session_id = ?::uuid AND outcome = 'OK'")
                    .param(sessionId)
                    .query(String.class)
                    .list();
            assertThat(purposes).contains("ROUND_GEN", "HOST_LINE", "FINALE", "MODERATION", "TTS");
            long cost = jdbc.sql("SELECT sum(cost_micros) FROM ai_call WHERE game_session_id = ?::uuid")
                    .param(sessionId)
                    .query(Long.class)
                    .single();
            assertThat(cost).isPositive();

            String audioId = party.screen
                    .state(s -> s.path("host").path("audioId").isString())
                    .path("host")
                    .path("audioId")
                    .asString();
            assertThat(party.host.get("/api/voice-lines/" + audioId).status()).isEqualTo(200);
            assertThat(party.host.get("/api/voice-lines/unknown").status()).isEqualTo(404);
        }
    }

    @Test
    void aiOutageUsesFallbackContentAndTheGameGoesOn() {
        FakeAi.install(FAKE, FakeAi.Mode.ERROR);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone first = party.phones.getFirst();
            assertThat(first.getSocket().error("dossier.add", Map.of("text", "I once fell asleep at a concert")))
                    .as("moderation fails closed")
                    .isEqualTo("MODERATION_UNAVAILABLE");
            party.captain().getSocket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            assertThat(first.getSocket().error("intake.submit", Map.of("answers", List.of("a", "b", "c"))))
                    .isEqualTo("MODERATION_UNAVAILABLE");
            party.captain().getSocket().ok("game.next", Map.of());
            party.screen.state(s -> !"INTAKE".equals(s.path("phase").asString()));
            JsonNode finale =
                    new Driver(party, List.of("TRUTH_OR_AI", "WHO_OF_US", "ANSWER_DUEL", "TRUTH_OR_AI")).playToFinale();
            assertThat(finale.path("finale").path("titles").size()).isEqualTo(3);
            assertThat(finale.path("finale").path("speech").asString()).isNotBlank();
            String sessionId = finale.path("sessionId").asString();
            assertThat(jdbc.sql("SELECT count(*) FROM ai_call WHERE game_session_id = ?::uuid AND outcome = 'ERROR'")
                            .param(sessionId)
                            .query(Integer.class)
                            .single())
                    .isPositive();
        }
    }

    @Test
    void invalidModelOutputIsRetriedOnceThenFallsBack() {
        FakeAi.install(FAKE, FakeAi.Mode.INVALID);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().getSocket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            party.captain().getSocket().ok("game.next", Map.of());
            JsonNode answering =
                    party.screen.state(s -> "ANSWERING".equals(s.path("phase").asString()));
            assertThat(answering.path("round").path("duelCount").asInt()).isEqualTo(3);
            String sessionId = jdbc.sql(
                            "SELECT id::text FROM game_session WHERE room_code = ? ORDER BY started_at DESC LIMIT 1")
                    .param(party.code)
                    .query(String.class)
                    .single();
            Await.until(
                    "two attempts recorded",
                    () -> jdbc.sql("SELECT count(*) FROM ai_call WHERE game_session_id = ?::uuid "
                                            + "AND purpose = 'ROUND_GEN' AND outcome = 'INVALID_JSON'")
                                    .param(sessionId)
                                    .query(Integer.class)
                                    .single()
                            >= 2);
            for (Party.Phone p : party.phones) {
                JsonNode st = p.getSocket()
                        .state(s -> s.path("you").path("assignments").size() == 2);
                st.path("you")
                        .path("assignments")
                        .forEach(a -> assertThat(a.path("prompt").asString()).doesNotStartWith("AI prompt"));
            }
        }
    }

    @Test
    void playAgainKeepsPlayersAndSkipsTheIntake() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().getSocket().ok("game.start", Map.of());
            new Driver(party, List.of("WHO_OF_US", "WHO_OF_US", "WHO_OF_US", "WHO_OF_US")).playToFinale();
            party.captain().getSocket().ok("game.again", Map.of());
            JsonNode lobby = party.screen.phase("LOBBY");
            assertThat(lobby.path("players").size()).isEqualTo(3);
            lobby.path("players")
                    .forEach(p -> assertThat(p.path("score").asInt()).isZero());

            party.captain().getSocket().ok("game.start", Map.of());
            JsonNode denied = party.screen.state(s -> s.path("paywall").isString());
            assertThat(denied.path("paywall").asString()).isEqualTo("PAYWALL_FREE_LIMIT");
            assertThat(party.captain()
                            .getSocket()
                            .state(s -> s.path("paywall").isString())
                            .path("paywall")
                            .asString())
                    .isEqualTo("PAYWALL_FREE_LIMIT");
            assertThat(party.phones
                                    .getFirst()
                                    .getSocket()
                                    .latest()
                                    .path("paywall")
                                    .isMissingNode()
                            || party.phones
                                    .getFirst()
                                    .id()
                                    .equals(party.captain().id()))
                    .isTrue();

            data.pass(party.hostId, Product.PARTY_PASS);
            party.screen.ok("paywall.dismiss", Map.of());
            party.captain().getSocket().ok("game.start", Map.of());
            JsonNode second = party.screen.phase("ANSWERING");
            assertThat(second.path("round").path("n").asInt()).isEqualTo(1);
            String previous = jdbc.sql(
                            "SELECT previous_session_id::text FROM game_session WHERE room_code = ? AND is_free = false")
                    .param(party.code)
                    .query(String.class)
                    .single();
            assertThat(previous).isNotNull();
        }
    }
    /** The room's AI budget, counted in the engine, reaches the gateway: rounds fall back and the ledger says so. */
    @Test
    void aRoomOverItsAiBudgetFallsBackWithoutStopping() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().getSocket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            RoomState room = registry.find(party.code).orElseThrow();
            room.call(r -> {
                r.getLlmCalls().set(40);
                r.getTtsCalls().set(60);
                return null;
            });
            party.screen.ok("game.next", Map.of());
            party.screen.phase("ANSWERING");
            String sessionId = jdbc.sql("SELECT id::text FROM game_session WHERE room_code = ?")
                    .param(party.code)
                    .query(String.class)
                    .single();
            Await.until(
                    "fallback recorded",
                    () -> jdbc.sql("SELECT count(*) FROM ai_call WHERE game_session_id = ?::uuid "
                                            + "AND purpose = 'ROUND_GEN' AND outcome = 'FALLBACK'")
                                    .param(sessionId)
                                    .query(Integer.class)
                                    .single()
                            > 0);
            JsonNode phone = party.phones
                    .getFirst()
                    .getSocket()
                    .state(s -> s.path("you").path("assignments").size() == 2);
            phone.path("you")
                    .path("assignments")
                    .forEach(a -> assertThat(a.path("prompt").asString()).doesNotStartWith("AI prompt"));
        }
    }

    /** Prompts stay off the shared screen, and the authors of answers are hidden until the reveal. */
    private static void screenKeepsTheSecretsOfTheStep(JsonNode s) {
        JsonNode round = s.path("round");
        if ("ANSWERING".equals(s.path("phase").asString())) {
            assertThat(round.path("prompt").isMissingNode())
                    .as("prompts stay off the shared screen")
                    .isTrue();
            assertThat(s.path("you").path("assignments").isMissingNode()).isTrue();
        }
        if ("VOTING".equals(s.path("phase").asString())
                && "ANSWER_DUEL".equals(round.path("kind").asString())) {
            round.path("options")
                    .forEach(o -> assertThat(o.path("authorId").isMissingNode())
                            .as("authors hidden")
                            .isTrue());
        }
        if ("REVEAL".equals(s.path("phase").asString())
                && "ANSWER_DUEL".equals(round.path("kind").asString())) {
            round.path("options")
                    .forEach(o -> assertThat(o.path("authorId").isString())
                            .as("authors revealed")
                            .isTrue());
        }
    }
}
