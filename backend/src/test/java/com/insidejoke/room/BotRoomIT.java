package com.insidejoke.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.GameSocket;
import com.insidejoke.support.Party;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Test bots over the real transport (roadmap R34): an admin's room takes them, anyone else's doesn't. */
class BotRoomIT extends AbstractIntegrationTest {

    private static final Map<String, Object> SETTINGS = Map.of("tone", "CHEEKY", "length", "SHORT", "mode", "STANDARD");

    @Test
    void anAdminsOwnerScreenAddsABotThatEveryoneSeesAsOne() {
        ApiClient admin = Party.signIn(port, json, FAKE, "boss@example.com");
        JsonNode created = admin.post("/api/rooms", SETTINGS).json();
        try (GameSocket screen =
                GameSocket.connect(port, json, created.path("screenToken").asString())) {
            assertThat(screen.state(s -> s.path("lobby").has("botsAllowed"))
                            .path("lobby")
                            .path("botsAllowed")
                            .asBoolean())
                    .isTrue();

            screen.ok("bot.add", Map.of());

            JsonNode player = screen.state(s -> s.path("players").size() == 1)
                    .path("players")
                    .get(0);
            assertThat(player.path("bot").asBoolean()).isTrue();
            assertThat(player.path("captain").asBoolean())
                    .as("a bot never leads")
                    .isFalse();
        }
    }

    @Test
    void anOrdinaryHostCanNotAddBots() {
        try (Party party = Party.create(port, json, FAKE, SETTINGS, 1)) {
            assertThat(party.screen.latest().path("lobby").has("botsAllowed")).isFalse();
            assertThat(party.screen.error("bot.add", Map.of())).isEqualTo("NOT_ALLOWED");
            assertThat(party.phones.getFirst().getSocket().error("bot.add", Map.of()))
                    .isEqualTo("NOT_ALLOWED");
        }
    }
}
