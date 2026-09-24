package com.insidejoke.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.GameSocket;
import com.insidejoke.support.Party;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SocketProtocolIT extends AbstractIntegrationTest {

    @BeforeEach
    void ai() {
        FakeAi.install(FAKE);
    }

    @Test
    void helloMustComeFirst() {
        GameSocket s = new GameSocket(port, json);
        assertThat(s.error("intake.submit", Map.of())).isEqualTo("BAD_MESSAGE");
        Await.until("closed", () -> Integer.valueOf(4401).equals(s.closeCode()));
    }

    @Test
    void silentConnectionsAreDroppedAfterFiveSeconds() {
        GameSocket s = new GameSocket(port, json);
        Await.until("closed", Duration.ofSeconds(8), () -> Integer.valueOf(4401).equals(s.closeCode()));
    }

    @Test
    void unknownTokensAreRefused() {
        GameSocket s = new GameSocket(port, json);
        assertThat(s.error("hello", Map.of("token", "nope"))).isEqualTo("ROOM_NOT_FOUND");
        Await.until("closed", () -> Integer.valueOf(4404).equals(s.closeCode()));
    }

    @Test
    void pingPongWorksBeforeAndAfterHello() {
        GameSocket s = new GameSocket(port, json);
        assertThat(s.reply(s.send("ping", Map.of())).path("type").asString()).isEqualTo("pong");
        s.abort();
    }

    @Test
    void malformedAndOversizedMessagesGetErrorsButKeepTheConnection() {
        try (Party party = Party.create(port, json, FAKE, 1)) {
            GameSocket phone = party.phones.get(0).socket();
            phone.sendRaw("not json");
            phone.sendRaw("{\"v\":2,\"type\":\"ping\",\"reqId\":\"x-1\"}");
            assertThat(phone.reply("x-1").path("data").path("code").asString()).isEqualTo("BAD_MESSAGE");
            assertThat(phone.error("dance", Map.of())).isEqualTo("BAD_MESSAGE");
            assertThat(phone.error("dossier.add", Map.of("text", "y".repeat(5000))))
                    .isEqualTo("BAD_MESSAGE");
            assertThat(phone.error("hello", Map.of("token", party.phones.get(0).token())))
                    .isEqualTo("BAD_MESSAGE");
            assertThat(phone.reply(phone.send("ping", Map.of())).path("type").asString())
                    .isEqualTo("pong");
        }
    }

    @Test
    void moreThanTenMessagesPerSecondAreRateLimited() {
        try (Party party = Party.create(port, json, FAKE, 1)) {
            GameSocket phone = party.phones.get(0).socket();
            for (int i = 0; i < 15; i++) {
                phone.send("ping", Map.of());
            }
            Await.until("rate limit error", () -> phone.rawFrames().stream().anyMatch(f -> f.contains("RATE_LIMITED")));
            assertThat(phone.rawFrames().stream()
                            .filter(f -> f.contains("RATE_LIMITED"))
                            .allMatch(f -> f.contains("\"reqId\"")))
                    .as("the client can tell which request was refused")
                    .isTrue();
        }
    }

    @Test
    void foreignOriginsCannotConnect() {
        Throwable thrown = catchThrowable(() -> new GameSocket(port, json, "https://evil.example"));
        while (thrown != null && !(thrown instanceof WebSocketHandshakeException)) {
            thrown = thrown.getCause();
        }
        assertThat(thrown).as("handshake refused").isNotNull();
        assertThat(((WebSocketHandshakeException) thrown).getResponse().statusCode())
                .isEqualTo(403);
    }

    @Test
    void reconnectingWithTheSameTokenRestoresTheSeat() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone p = party.phones.get(1);
            p.socket().abort();
            party.screen.state(s -> s.path("players").size() > 1
                    && !s.path("players").get(1).path("connected").asBoolean());
            try (GameSocket again = GameSocket.connect(port, json, p.token())) {
                JsonNode state = again.latest();
                assertThat(state.path("you").path("playerId").asString()).isEqualTo(p.id());
                assertThat(state.path("you").path("name").asString()).isEqualTo(p.name());
                party.screen.state(s -> s.path("players").size() > 1
                        && s.path("players").get(1).path("connected").asBoolean());
            }
        }
    }

    /** The engine's decisions reach the phones: a kick is a "kicked" frame, a 4403 close, and a token that is dead. */
    @Test
    void aKickedPhoneIsToldDisconnectedAndCannotComeBack() {
        try (Party party = Party.create(port, json, FAKE, 4)) {
            Party.Phone victim = party.phones.get(3);
            party.screen.ok("player.kick", Map.of("playerId", victim.id()));
            Await.until("kick notice", () -> victim.socket().received("kicked"));
            Await.until(
                    "socket closed",
                    () -> Integer.valueOf(4403).equals(victim.socket().closeCode()));
            assertThat(party.screen.state(s -> s.path("players").size() == 3)).isNotNull();
            GameSocket again = new GameSocket(port, json);
            assertThat(again.error("hello", Map.of("token", victim.token()))).isEqualTo("ROOM_NOT_FOUND");
            again.abort();
        }
    }

    /** Closing the room tells every connection and ends it; the tokens no longer open anything. */
    @Test
    void aClosedRoomSaysGoodbyeToEveryConnection() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.screen.ok("room.close", Map.of());
            Await.until("phones told", () -> party.phones.get(1).socket().received("closed"));
            Await.until("screen told", () -> party.screen.received("closed"));
            assertThat(party.screen.latest().path("phase").asString()).isEqualTo("CLOSED");
            GameSocket late = new GameSocket(port, json);
            assertThat(late.error("hello", Map.of("token", party.phones.get(0).token())))
                    .isEqualTo("ROOM_NOT_FOUND");
            late.abort();
        }
    }

    /** A client re-sends what was in flight when its connection dropped: answered again, applied once. */
    @Test
    void aRequestReSentAfterAReconnectIsAnsweredButAppliedOnce() {
        FakeAi.install(FAKE, FakeAi.Mode.OK, 1500);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone p = party.phones.get(0);
            Map<String, Object> secret = Map.of("text", "Sleeps with a night light on");
            p.socket().send("dossier.add", secret, "resent-1");
            Await.until("the secret is being checked", () -> checksOf("night light") == 1);
            p.socket().abort();

            try (GameSocket again = GameSocket.connect(port, json, p.token())) {
                JsonNode reply = again.reply(again.send("dossier.add", secret, "resent-1"));
                assertThat(reply.path("type").asString())
                        .as("answered once the check is done")
                        .isEqualTo("ok");
                assertThat(reply.path("data").path("secretsLeft").asInt()).isEqualTo(9);
                again.abort();
            }
            try (GameSocket third = GameSocket.connect(port, json, p.token())) {
                JsonNode reply = third.reply(third.send("dossier.add", secret, "resent-1"));
                assertThat(reply.path("type").asString())
                        .as("the answer is replayed")
                        .isEqualTo("ok");
                assertThat(reply.path("data").path("secretsLeft").asInt()).isEqualTo(9);
                third.abort();
            }

            assertThat(party.screen.state(s -> s.path("secrets").asInt() == 1)).isNotNull();
            assertThat(checksOf("night light")).as("checked by the model once").isEqualTo(1);
            assertThat(party.screen.latest().path("secrets").asInt()).isEqualTo(1);
        }
    }

    private static long checksOf(String text) {
        return FAKE.requests("POST", "/anthropic/v1/messages").stream()
                .filter(r -> r.body().contains(text))
                .count();
    }

    /** The protocol omits empty fields instead of sending null: clients (and older clients) rely on that shape. */
    @Test
    void stateFramesNeverCarryNulls() {
        FakeAi.install(FAKE);
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().socket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            party.completeIntake();
            party.screen.phase("ANSWERING");
            for (GameSocket socket : List.of(
                    party.screen,
                    party.phones.get(0).socket(),
                    party.phones.get(1).socket())) {
                for (JsonNode state : socket.states()) {
                    assertThat(nullPaths(state, "$"))
                            .as("null fields in a state frame")
                            .isEmpty();
                }
            }
        }
    }

    private static List<String> nullPaths(JsonNode node, String path) {
        List<String> found = new ArrayList<>();
        if (node.isNull()) {
            found.add(path);
        } else if (node.isObject()) {
            node.properties().forEach(e -> found.addAll(nullPaths(e.getValue(), path + "." + e.getKey())));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                found.addAll(nullPaths(node.get(i), path + "[" + i + "]"));
            }
        }
        return found;
    }
}
