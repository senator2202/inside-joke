package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Party;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/** The app as run locally from the README: no Anthropic key, strict secret moderation on (the default). */
@TestPropertySource(properties = "test.context = no-ai-key")
class NoAiKeyIT extends AbstractIntegrationTest {

    static {
        // Set before this class's Spring context is built, so the gateway starts with no key at all.
        System.setProperty("test.anthropic.api-key", "");
    }

    @AfterAll
    static void restoreKey() {
        System.clearProperty("test.anthropic.api-key");
    }

    @Test
    void withoutAnAiKeyTheIntakeWorksAndSecretsAreNotOffered() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone p = party.phones.get(0);
            JsonNode lobby = p.socket().latest();
            assertThat(lobby.path("secretsOpen").asBoolean(true))
                    .as("no model to check secrets: don't offer them")
                    .isFalse();
            assertThat(party.screen.latest().path("secretsOpen").asBoolean(true))
                    .isFalse();
            assertThat(p.socket().error("dossier.add", Map.of("text", "A harmless story")))
                    .isEqualTo("MODERATION_UNAVAILABLE");

            party.captain().socket().ok("game.start", Map.of());
            p.socket().phase("INTAKE");
            JsonNode blocked = p.socket()
                    .reply(p.socket()
                            .send(
                                    "intake.submit",
                                    Map.of("answers", List.of("Pizza", "call +44 20 7946 0958", "Karaoke"))));
            assertThat(blocked.path("data").path("code").asString())
                    .as("rules still apply")
                    .isEqualTo("MODERATION_BLOCKED");
            p.socket().ok("intake.submit", Map.of("answers", List.of("Pizza", "Dancing", "Karaoke")));
            assertThat(party.screen.state(s -> s.path("intake").path("done").asInt() == 1))
                    .isNotNull();
        }
    }
}
