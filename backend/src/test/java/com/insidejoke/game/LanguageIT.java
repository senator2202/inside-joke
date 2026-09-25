package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.FakeHttp;
import com.insidejoke.support.FakeResend;
import com.insidejoke.support.Party;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class LanguageIT extends AbstractIntegrationTest {

    private static final Pattern CYRILLIC = Pattern.compile("\\p{IsCyrillic}");

    private static Map<String, Object> russian() {
        return Map.of("tone", "CHEEKY", "length", "SHORT", "mode", "STANDARD", "language", "ru");
    }

    private List<String> systemPrompts() {
        return FAKE.requests("POST", "/anthropic/v1/messages").stream()
                .map(r -> json.readTree(r.body()).path("system").toString())
                .toList();
    }

    @Test
    void aRussianRoomSpeaksRussianAndTellsTheAiToo() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, russian(), 3)) {
            assertThat(client().get("/api/rooms/" + party.code)
                            .json()
                            .path("language")
                            .asString())
                    .isEqualTo("ru");
            JsonNode lobby = party.screen.state(s -> s.path("host").path("text").isString());
            assertThat(lobby.path("settings").path("language").asString()).isEqualTo("ru");
            assertThat(lobby.path("host").path("text").asString()).containsPattern(CYRILLIC);

            party.captain().getSocket().ok("game.start", Map.of());
            JsonNode intake = party.phones
                    .getFirst()
                    .getSocket()
                    .state(s -> "INTAKE".equals(s.path("phase").asString()));
            intake.path("intake")
                    .path("questions")
                    .forEach(q -> assertThat(q.asString()).containsPattern(CYRILLIC));

            party.screen.ok("game.next", Map.of());
            party.screen.phase("ANSWERING");
            Await.until("round generated", () -> systemPrompts().stream().anyMatch(p -> p.contains("in Russian")));
            assertThat(systemPrompts())
                    .anyMatch(p -> p.contains("\\u041a\\u0442\\u043e \\u0438\\u0437 \\u043d\\u0430\\u0441")
                            || p.contains("Кто из нас"));
            Await.until(
                    "language recorded",
                    () -> "ru"
                            .equals(jdbc.sql("SELECT language FROM game_session WHERE room_code = ?")
                                    .param(party.code)
                                    .query(String.class)
                                    .optional()
                                    .orElse(null)));
        }
    }

    @Test
    void whenTheAiIsDownTheFallbackContentIsRussianToo() {
        FakeAi.install(FAKE, FakeAi.Mode.ERROR);
        try (Party party = Party.create(port, json, FAKE, russian(), 3)) {
            party.captain().getSocket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            party.screen.ok("game.next", Map.of());
            party.screen.phase("ANSWERING");
            JsonNode phone = party.phones
                    .getFirst()
                    .getSocket()
                    .state(s -> s.path("you").path("assignments").size() == 2);
            phone.path("you")
                    .path("assignments")
                    .forEach(a -> assertThat(a.path("prompt").asString()).containsPattern(CYRILLIC));
        }
    }

    @Test
    void englishStaysTheDefaultAndTheLobbyCanSwitchLanguage() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            assertThat(client().get("/api/rooms/" + party.code)
                            .json()
                            .path("language")
                            .asString())
                    .isEqualTo("en");
            party.screen.ok("game.settings", Map.of("language", "ru"));
            party.screen.state(
                    s -> "ru".equals(s.path("settings").path("language").asString()));
            assertThat(party.screen.error("game.settings", Map.of("language", "de")))
                    .isEqualTo("VALIDATION_FAILED");
            party.captain().getSocket().ok("game.start", Map.of());
            JsonNode intake = party.phones
                    .get(1)
                    .getSocket()
                    .state(s -> "INTAKE".equals(s.path("phase").asString()));
            intake.path("intake")
                    .path("questions")
                    .forEach(q -> assertThat(q.asString()).containsPattern(CYRILLIC));
        }
    }

    @Test
    void theSignInEmailFollowsTheSiteLanguage() {
        FakeResend.accept(FAKE);
        client().post("/api/auth/magic-link", Map.of("email", uniqueEmail("ru"), "language", "ru"));
        FakeHttp.Recorded mail = FAKE.requests("POST", "/resend/emails").getLast();
        JsonNode body = json.readTree(mail.body());
        assertThat(body.path("subject").asString()).startsWith("Ваш код Inside Joke: ");
        assertThat(body.path("html").asString()).contains("Войти на этом устройстве");

        client().post("/api/auth/magic-link", Map.of("email", uniqueEmail("en")));
        JsonNode english =
                json.readTree(FAKE.requests("POST", "/resend/emails").getLast().body());
        assertThat(english.path("subject").asString()).startsWith("Your Inside Joke code: ");
    }
}
