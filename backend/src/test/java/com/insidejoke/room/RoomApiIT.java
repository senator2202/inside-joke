package com.insidejoke.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.game.GameProperties;
import com.insidejoke.game.RoomRegistryService;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.GameSocket;
import com.insidejoke.support.Party;
import com.insidejoke.support.PropertyDefaults;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class RoomApiIT extends AbstractIntegrationTest {

    @BeforeEach
    void ai() {
        FakeAi.install(FAKE);
    }

    private ApiClient host() {
        return Party.signIn(port, json, FAKE, uniqueEmail("rooms"));
    }

    private static Map<String, Object> settings(String tone) {
        Map<String, Object> m = new HashMap<>();
        m.put("tone", tone);
        m.put("length", "SHORT");
        m.put("mode", "STANDARD");
        return m;
    }

    @Test
    void creatingARoomRequiresSignIn() {
        assertThat(client().post("/api/rooms", settings("FAMILY")).status()).isEqualTo(401);
    }

    @Test
    void createValidatesSettings() {
        ApiClient host = host();
        assertThat(host.post("/api/rooms", settings("WILD")).errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(host.post("/api/rooms", Map.of("tone", "FAMILY")).errorCode())
                .isEqualTo("VALIDATION_FAILED");
        ApiClient.Resp spicy = host.post("/api/rooms", settings("SPICY"));
        assertThat(spicy.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(spicy.json()
                        .path("error")
                        .path("details")
                        .path("fields")
                        .path("adultsConfirmed")
                        .isString())
                .isTrue();
        Map<String, Object> german = settings("FAMILY");
        german.put("language", "de");
        assertThat(host.post("/api/rooms", german).errorCode()).isEqualTo("VALIDATION_FAILED");

        Map<String, Object> ok = settings("SPICY");
        ok.put("adultsConfirmed", true);
        ok.put("language", "en");
        ApiClient.Resp created = host.post("/api/rooms", ok);
        assertThat(created.status()).isEqualTo(201);
        String code = created.json().path("code").asString();
        assertThat(code).hasSize(4).matches("[" + RoomRegistryService.ALPHABET + "]{4}");
        assertThat(created.json().path("joinUrl").asString()).isEqualTo("http://localhost/j/" + code);
        assertThat(created.json().path("screenToken").asString()).hasSizeGreaterThan(20);
    }

    @Test
    void drainModeRefusesNewRooms() {
        ApiClient host = host();
        jdbc.sql("UPDATE app_setting SET value = 'true'::jsonb WHERE key = 'drain_mode'")
                .update();
        settings.refresh();
        assertThat(host.post("/api/rooms", settings("FAMILY")).errorCode()).isEqualTo("DRAIN_MODE");
    }

    @Test
    void statusDescribesTheRoomForTheJoinScreen() {
        try (Party party = Party.create(port, json, FAKE, 1)) {
            JsonNode status =
                    client().get("/api/rooms/" + party.code.toLowerCase()).json();
            assertThat(status.path("code").asString()).isEqualTo(party.code);
            assertThat(status.path("phase").asString()).isEqualTo("LOBBY");
            assertThat(status.path("players").asInt()).isEqualTo(1);
            assertThat(status.path("joinable").asBoolean()).isTrue();
            assertThat(status.path("audienceOpen").asBoolean()).isFalse();
        }
        assertThat(client().get("/api/rooms/ZZZZ").errorCode()).isEqualTo("ROOM_NOT_FOUND");
        assertThat(client().get("/api/rooms/ZZZZ").status()).isEqualTo(404);
    }

    @Test
    void joiningValidatesNames() {
        try (Party party = Party.create(port, json, FAKE, 1)) {
            String path = "/api/rooms/" + party.code + "/players";
            assertThat(client().post(path, Map.of("name", "  ")).errorCode()).isEqualTo("NAME_INVALID");
            assertThat(client().post(path, Map.of("name", "ThirteenChars")).errorCode())
                    .isEqualTo("NAME_INVALID");
            assertThat(client().post(path, Map.of("name", "bit.ly/x")).errorCode())
                    .isEqualTo("NAME_INVALID");
            assertThat(client().post(path, Map.of("name", "player1")).errorCode())
                    .isEqualTo("NAME_TAKEN");
            ApiClient.Resp ok = client().post(path, Map.of("name", " Masha  ", "emoji", "not-an-emoji"));
            assertThat(ok.status()).isEqualTo(201);
            assertThat(ok.json().path("playerId").asString()).startsWith("p");
            JsonNode lobby = party.screen.state(s -> s.path("players").size() == 2);
            assertThat(lobby.path("players").path(1).path("name").asString()).isEqualTo("Masha");
            assertThat(lobby.path("players").path(1).path("emoji").asString()).isNotEqualTo("not-an-emoji");
        }
    }

    @Test
    void roomsHoldEightPlayersAndCanBeLocked() {
        try (Party party = Party.create(port, json, FAKE, 8)) {
            String path = "/api/rooms/" + party.code + "/players";
            assertThat(client().post(path, Map.of("name", "Ninth")).errorCode()).isEqualTo("ROOM_FULL");
            assertThat(client().get("/api/rooms/" + party.code)
                            .json()
                            .path("full")
                            .asBoolean())
                    .isTrue();
            party.screen.ok(
                    "player.kick", Map.of("playerId", party.phones.get(7).id()));
            party.screen.ok("room.lock", Map.of("locked", true));
            party.screen.state(s -> s.path("locked").asBoolean());
            assertThat(client().post(path, Map.of("name", "Late")).errorCode()).isEqualTo("ROOM_LOCKED");
            party.screen.ok("room.lock", Map.of("locked", false));
            party.screen.state(s -> !s.path("locked").asBoolean());
            assertThat(client().post(path, Map.of("name", "Late")).status()).isEqualTo(201);
        }
    }

    @Test
    void joiningAfterTheIntakeIsRefused() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().getSocket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            assertThat(client().post("/api/rooms/" + party.code + "/players", Map.of("name", "Early"))
                            .status())
                    .isEqualTo(201);
            party.screen.ok("game.next", Map.of());
            party.screen.phase("ANSWERING");
            assertThat(client().post("/api/rooms/" + party.code + "/players", Map.of("name", "Late"))
                            .errorCode())
                    .isEqualTo("ROOM_IN_PROGRESS");
            assertThat(client().get("/api/rooms/" + party.code)
                            .json()
                            .path("joinable")
                            .asBoolean())
                    .isFalse();
        }
    }

    @Test
    void onlyTheOwnerGetsTheScreenTokenBack() {
        try (Party party = Party.create(port, json, FAKE, 0)) {
            JsonNode mine =
                    party.host.get("/api/rooms/" + party.code + "/owner-token").json();
            assertThat(mine.path("screenToken").asString()).isEqualTo(party.screenToken);
            assertThat(host().get("/api/rooms/" + party.code + "/owner-token").errorCode())
                    .isEqualTo("FORBIDDEN");
            assertThat(client().get("/api/rooms/" + party.code + "/owner-token").status())
                    .isEqualTo(401);
        }
    }

    @Test
    void audienceOnlyInStreamerModeAndUpToTheCap() {
        try (Party standard = Party.create(port, json, FAKE, 0)) {
            assertThat(client().get("/api/rooms/" + standard.code)
                            .json()
                            .path("audienceKey")
                            .isNull())
                    .isTrue();
            assertThat(client().post("/api/audiences/" + standard.code + "/viewers", Map.of())
                            .errorCode())
                    .isEqualTo("ROOM_NOT_FOUND");
        }
        jdbc.sql("UPDATE app_setting SET value = '1'::jsonb WHERE key = 'audience_cap'")
                .update();
        settings.refresh();
        try (Party stream =
                Party.create(port, json, FAKE, Map.of("tone", "FAMILY", "length", "SHORT", "mode", "STREAMER"), 0)) {
            String key = client().get("/api/rooms/" + stream.code)
                    .json()
                    .path("audienceKey")
                    .asString();
            assertThat(key).hasSize(10).doesNotContain(stream.code);
            String token = client().post("/api/audiences/" + key.toLowerCase() + "/viewers", Map.of())
                    .json()
                    .path("audienceToken")
                    .asString();
            try (var viewer = GameSocket.connect(port, json, token)) {
                stream.screen.state(s -> s.path("lobby").path("audienceCount").asInt() == 1);
                assertThat(client().post("/api/audiences/" + key + "/viewers", Map.of())
                                .errorCode())
                        .isEqualTo("AUDIENCE_FULL");
                assertThat(viewer.latest().path("you").path("role").asString()).isEqualTo("AUDIENCE");
            }
            assertThat(stream.screen.latest().path("lobby").path("audienceUrl").asString())
                    .isEqualTo("http://localhost/w/" + key);
            assertThat(client().post("/api/audiences/" + stream.code + "/viewers", Map.of())
                            .errorCode())
                    .as("the room code is not a viewer link")
                    .isEqualTo("ROOM_NOT_FOUND");
        }
    }

    @Test
    void aHiddenCodeNeverReachesTheStreamOrItsViewers() {
        FakeAi.install(FAKE);
        try (Party stream = Party.create(
                port,
                json,
                FAKE,
                Map.of("tone", "FAMILY", "length", "SHORT", "mode", "STREAMER", "hideCode", true),
                3)) {
            JsonNode ownerLobby = stream.screen.latest();
            String audienceUrl = ownerLobby.path("lobby").path("audienceUrl").asString();
            assertThat(audienceUrl).as("the viewer link is shown on stream").doesNotContain(stream.code);
            String key = audienceUrl.substring(audienceUrl.lastIndexOf('/') + 1);
            String viewerToken = client().post("/api/audiences/" + key + "/viewers", Map.of())
                    .json()
                    .path("audienceToken")
                    .asString();
            String copyToken = stream.host
                    .post("/api/rooms/" + stream.code + "/screens", Map.of())
                    .json()
                    .path("screenToken")
                    .asString();
            try (var viewer = GameSocket.connect(port, json, viewerToken);
                    var copy = GameSocket.connect(port, json, copyToken)) {
                stream.captain().getSocket().ok("game.start", Map.of());
                viewer.state(s -> "INTAKE".equals(s.path("phase").asString()));
                copy.state(s -> "INTAKE".equals(s.path("phase").asString()));
                assertThat(String.join("\n", viewer.rawFrames())).doesNotContain(stream.code);
                assertThat(String.join("\n", copy.rawFrames())).doesNotContain(stream.code);
            }
        }
    }

    @Test
    void aRoomRefusesScreenCopiesBeyondTheLimit() {
        try (Party party = Party.create(port, json, FAKE, 0)) {
            ApiClient remote = client();
            int limit = PropertyDefaults.of("app.game", GameProperties.class).maxScreenCopies();
            for (int i = 0; i < limit; i++) {
                assertThat(remote.post("/api/rooms/" + party.code + "/screens", Map.of())
                                .status())
                        .isEqualTo(201);
            }
            ApiClient.Resp refused = remote.post("/api/rooms/" + party.code + "/screens", Map.of());
            assertThat(refused.status()).isEqualTo(409);
            assertThat(refused.errorCode()).isEqualTo("SCREENS_FULL");
        }
    }

    @Test
    void joinAttemptsAreRateLimitedPerAddress() {
        try (Party party = Party.create(port, json, FAKE, 0)) {
            ApiClient guest = client();
            String last = null;
            for (int i = 0; i < 21; i++) {
                last = guest.post("/api/rooms/" + party.code + "/players", Map.of("name", ""))
                        .errorCode();
            }
            assertThat(last).isEqualTo("RATE_LIMITED");
        }
    }

    @Test
    void withAModelConfiguredSecretsAreOpen() {
        try (Party party = Party.create(port, json, FAKE, 1)) {
            assertThat(party.phones
                            .getFirst()
                            .getSocket()
                            .latest()
                            .path("secretsOpen")
                            .asBoolean())
                    .isTrue();
        }
    }

    @Test
    void emojiListIsPublic() {
        assertThat(client().get("/api/rooms/emojis").json().size()).isEqualTo(24);
    }
}
