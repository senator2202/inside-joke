package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.Await;
import com.insidejoke.support.FakeAi;
import com.insidejoke.support.GameSocket;
import com.insidejoke.support.Party;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class GameRulesIT extends AbstractIntegrationTest {

    @Autowired
    GameEngineService engine;

    @Autowired
    RoomRegistryService registry;

    @BeforeEach
    void ai() {
        FakeAi.install(FAKE);
    }

    private RoomState room(Party party) {
        return registry.find(party.code).orElseThrow();
    }

    /** Starts the game and skips the intake, so round 1 (always an answer duel) is being written. */
    private JsonNode toAnswering(Party party) {
        party.captain().socket().ok("game.start", Map.of());
        party.screen.phase("INTAKE");
        party.screen.ok("game.next", Map.of());
        return party.screen.phase("ANSWERING");
    }

    private void answerEverything(Party party, String text) {
        for (Party.Phone p : party.phones) {
            JsonNode st =
                    p.socket().state(s -> s.path("you").path("assignments").size() == 2);
            for (JsonNode a : st.path("you").path("assignments")) {
                p.socket()
                        .ok(
                                "answer.submit",
                                Map.of("duelId", a.path("duelId").asString(), "text", text + " from " + p.name()));
            }
        }
    }

    @Test
    void aGameNeedsThreePlayers() {
        try (Party party = Party.create(port, json, FAKE, 2)) {
            assertThat(party.captain().socket().error("game.start", Map.of())).isEqualTo("NOT_ENOUGH_PLAYERS");
            JsonNode lobby = party.screen.phase("LOBBY");
            assertThat(lobby.path("lobby").path("joinUrl").asString()).isEqualTo("http://localhost/j/" + party.code);
            assertThat(lobby.path("lobby").path("minPlayers").asInt()).isEqualTo(3);
        }
    }

    @Test
    void permissionsFollowTheRoleMatrix() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone captain = party.captain();
            Party.Phone player = party.phones.stream()
                    .filter(p -> !p.id().equals(captain.id()))
                    .findFirst()
                    .orElseThrow();
            assertThat(player.socket().error("game.start", Map.of())).isEqualTo("NOT_ALLOWED");
            assertThat(player.socket().error("player.kick", Map.of("playerId", captain.id())))
                    .isEqualTo("NOT_ALLOWED");
            assertThat(captain.socket().error("game.settings", Map.of("length", "LONG")))
                    .isEqualTo("NOT_ALLOWED");
            assertThat(captain.socket().error("room.close", Map.of())).isEqualTo("NOT_ALLOWED");
            assertThat(party.screen.error("dossier.add", Map.of("text", "anything")))
                    .isEqualTo("NOT_ALLOWED");
            assertThat(party.screen.error("audience.vote", Map.of("optionId", "A")))
                    .isEqualTo("NOT_ALLOWED");

            String copyToken = party.host
                    .post("/api/rooms/" + party.code + "/screens", Map.of())
                    .json()
                    .path("screenToken")
                    .asString();
            try (GameSocket copy = GameSocket.connect(port, json, copyToken)) {
                assertThat(copy.latest().path("you").path("role").asString()).isEqualTo("SCREEN");
                assertThat(copy.error("game.start", Map.of())).isEqualTo("NOT_ALLOWED");
                assertThat(copy.error("game.pause", Map.of())).isEqualTo("NOT_ALLOWED");
            }

            party.screen.ok("captain.set", Map.of("playerId", player.id()));
            assertThat(player.socket()
                            .state(s ->
                                    "CAPTAIN".equals(s.path("you").path("role").asString())))
                    .isNotNull();
            assertThat(captain.socket()
                            .state(s ->
                                    "PLAYER".equals(s.path("you").path("role").asString())))
                    .isNotNull();
        }
    }

    @Test
    void ownerChangesSettingsInTheLobbyAndSpicyNeedsConfirmation() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            JsonNode error = party.screen.reply(party.screen.send("game.settings", Map.of("tone", "SPICY")));
            assertThat(error.path("data").path("code").asString()).isEqualTo("VALIDATION_FAILED");
            party.screen.ok("game.settings", Map.of("tone", "SPICY", "length", "LONG", "adultsConfirmed", true));
            JsonNode s = party.screen.state(
                    x -> "SPICY".equals(x.path("settings").path("tone").asString()));
            assertThat(s.path("settings").path("length").asString()).isEqualTo("LONG");
            toAnswering(party);
            assertThat(party.screen.error("game.settings", Map.of("length", "SHORT")))
                    .isEqualTo("INVALID_PHASE");
            assertThat(party.screen.latest().path("round").path("of").asInt()).isEqualTo(10);
        }
    }

    @Test
    void kickedPlayerIsDisconnectedAndCannotReturn() {
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

    @Test
    void ownerPausesAndResumesAndTimersFreeze() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            party.screen.ok("game.pause", Map.of());
            JsonNode paused = party.screen.state(s -> s.path("paused").isObject());
            assertThat(paused.path("paused").path("reason").asString()).isEqualTo("OWNER");
            assertThat(paused.path("deadline").isMissingNode()).isTrue();
            clock.advance(Duration.ofMinutes(5));
            engine.tickAll();
            assertThat(party.screen
                            .state(s -> s.path("paused").isObject())
                            .path("phase")
                            .asString())
                    .isEqualTo("ANSWERING");
            party.screen.ok("game.resume", Map.of());
            JsonNode resumed = party.screen.state(s -> s.path("paused").isMissingNode());
            assertThat(resumed.path("deadline").asLong()).isGreaterThan(clock.millis() + 50_000);
            assertThat(party.screen.error("game.resume", Map.of())).isEqualTo("INVALID_PHASE");
        }
    }

    @Test
    void losingTheSharedScreenPausesTheGameUntilTheOwnerContinues() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            RoomState room = room(party);
            party.screen.abort();
            Await.until("server noticed the screen left", () -> room.call(r -> r.getOwnerScreens() == 0));
            clock.advance(Duration.ofSeconds(11));
            engine.tickAll();
            JsonNode onPhone =
                    party.phones.get(0).socket().state(s -> s.path("paused").isObject());
            assertThat(onPhone.path("paused").path("reason").asString()).isEqualTo("SCREEN_LOST");

            try (GameSocket back = GameSocket.connect(port, json, party.screenToken)) {
                JsonNode welcome =
                        back.state(s -> s.path("paused").path("welcomeBack").asBoolean());
                assertThat(welcome.path("phase").asString()).isEqualTo("ANSWERING");
                back.ok("game.resume", Map.of());
                assertThat(back.state(s -> s.path("paused").isMissingNode())
                                .path("deadline")
                                .isNumber())
                        .isTrue();
            }
        }
    }

    @Test
    void fewerThanThreePlayersWaitsThenEndsTheGame() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            RoomState room = room(party);
            Party.Phone leaver = party.phones.get(2);
            leaver.socket().abort();
            Await.until(
                    "server noticed the player left",
                    () -> room.call(r -> r.getPlayers().get(leaver.id()).getConnections() == 0));
            assertThat(party.screen
                            .state(s -> s.path("players").size() > 2
                                    && !s.path("players")
                                            .get(2)
                                            .path("connected")
                                            .asBoolean())
                            .path("paused")
                            .isMissingNode())
                    .as("20 s grace before anything happens")
                    .isTrue();

            clock.advance(Duration.ofSeconds(21));
            engine.tickAll();
            JsonNode waiting = party.screen.state(s -> s.path("waiting").isObject());
            assertThat(waiting.path("paused").path("reason").asString()).isEqualTo("WAITING_FOR_PLAYERS");
            assertThat(waiting.path("waiting").path("missing").toString()).contains(leaver.name());

            clock.advance(Duration.ofSeconds(61));
            engine.tickAll();
            JsonNode finale = party.screen.phase("FINALE");
            assertThat(finale.path("finale").path("standings").size()).isEqualTo(3);
            Await.until(
                    "session closed",
                    () -> jdbc.sql("SELECT count(*) FROM game_session WHERE room_code = ? "
                                            + "AND end_reason = 'NOT_ENOUGH_PLAYERS'")
                                    .param(party.code)
                                    .query(Integer.class)
                                    .single()
                            == 1);
        }
    }

    @Test
    void aReturningPlayerResumesTheGame() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            RoomState room = room(party);
            Party.Phone leaver = party.phones.get(1);
            leaver.socket().abort();
            Await.until(
                    "left", () -> room.call(r -> r.getPlayers().get(leaver.id()).getConnections() == 0));
            clock.advance(Duration.ofSeconds(21));
            engine.tickAll();
            party.screen.state(s -> s.path("waiting").isObject());
            try (GameSocket back = GameSocket.connect(port, json, leaver.token())) {
                JsonNode resumed = party.screen.state(s -> s.path("paused").isMissingNode());
                assertThat(resumed.path("phase").asString()).isEqualTo("ANSWERING");
                assertThat(back.latest().path("you").path("assignments").size()).isEqualTo(2);
            }
        }
    }

    @Test
    void secretsAreModeratedAndLimited() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            Party.Phone p = party.phones.get(0);
            Party.Phone friend = party.phones.get(1);
            assertThat(p.socket().error("dossier.add", Map.of("text", "Call me on +44 20 7946 0958")))
                    .isEqualTo("MODERATION_BLOCKED");
            assertThat(p.socket().error("dossier.add", Map.of("text", "She was diagnosed with something")))
                    .isEqualTo("MODERATION_BLOCKED");
            assertThat(p.socket().error("dossier.add", Map.of("text", "BANNED story about work")))
                    .isEqualTo("MODERATION_BLOCKED");
            assertThat(p.socket().error("dossier.add", Map.of("aboutPlayerId", "nobody", "text", "x")))
                    .isEqualTo("NOT_FOUND");
            assertThat(p.socket().error("dossier.add", Map.of("text", "x".repeat(201))))
                    .isEqualTo("VALIDATION_FAILED");
            JsonNode ok = p.socket()
                    .ok(
                            "dossier.add",
                            Map.of("aboutPlayerId", friend.id(), "text", "Sings in the shower every morning"));
            assertThat(ok.path("secretsLeft").asInt()).isEqualTo(9);
            for (int i = 0; i < 9; i++) {
                p.socket().ok("dossier.add", Map.of("text", "Harmless story number " + i));
            }
            assertThat(p.socket().error("dossier.add", Map.of("text", "One more")))
                    .isEqualTo("DOSSIER_LIMIT");
            assertThat(p.socket().state(s -> s.path("you").path("secretsLeft").asInt() == 0))
                    .isNotNull();
            assertThat(party.screen.state(s -> s.path("secrets").asInt() == 10)).isNotNull();
            assertThat(jdbc.sql("SELECT count(*) FROM moderation_event WHERE stage = 'DOSSIER' AND action = 'BLOCKED'")
                            .query(Integer.class)
                            .single())
                    .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void intakeAnswersAreModeratedPerQuestion() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().socket().ok("game.start", Map.of());
            Party.Phone p = party.phones.get(0);
            p.socket().phase("INTAKE");
            JsonNode reply = p.socket()
                    .reply(p.socket()
                            .send(
                                    "intake.submit",
                                    Map.of("answers", List.of("Pizza", "Visit www.example.com", "Karaoke"))));
            assertThat(reply.path("data").path("code").asString()).isEqualTo("MODERATION_BLOCKED");
            assertThat(reply.path("data").path("details").path("rejected").toString())
                    .isEqualTo("[1]");
            JsonNode byModel = p.socket()
                    .reply(p.socket().send("intake.submit", Map.of("answers", List.of("Pizza", "BANNED", "Karaoke"))));
            assertThat(byModel.path("data").path("details").path("rejected").toString())
                    .isEqualTo("[1]");
            p.socket().ok("intake.submit", Map.of("answers", List.of("Pizza", "Dancing", "Karaoke")));
            JsonNode s = party.screen.state(x -> x.path("intake").path("done").asInt() == 1);
            assertThat(s.path("players").get(0).path("status").asString()).isEqualTo("done");
            assertThat(p.socket().state(x -> !x.path("you").path("intakeNeeded").asBoolean(true)))
                    .isNotNull();
        }
    }

    @Test
    void answersAreCheckedByRulesAndByTheModelBeforeTheyAreShown() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            Party.Phone p = party.phones.get(0);
            JsonNode st =
                    p.socket().state(s -> s.path("you").path("assignments").size() == 2);
            String duelId =
                    st.path("you").path("assignments").get(0).path("duelId").asString();
            assertThat(p.socket().error("answer.submit", Map.of("duelId", duelId, "text", "see bit.ly/xyz")))
                    .isEqualTo("MODERATION_BLOCKED");
            assertThat(p.socket().error("answer.submit", Map.of("duelId", duelId, "text", "x".repeat(121))))
                    .isEqualTo("VALIDATION_FAILED");
            assertThat(p.socket().error("answer.submit", Map.of("duelId", "d999", "text", "hello")))
                    .isEqualTo("VALIDATION_FAILED");
            p.socket().ok("answer.submit", Map.of("duelId", duelId, "text", "BANNED joke"));
            String otherDuel =
                    st.path("you").path("assignments").get(1).path("duelId").asString();
            p.socket().ok("answer.submit", Map.of("duelId", otherDuel, "text", "A fine answer"));
            for (Party.Phone other : party.phones.subList(1, 3)) {
                JsonNode os = other.socket()
                        .state(s -> s.path("you").path("assignments").size() == 2);
                for (JsonNode a : os.path("you").path("assignments")) {
                    other.socket()
                            .ok(
                                    "answer.submit",
                                    Map.of("duelId", a.path("duelId").asString(), "text", "Answer by " + other.name()));
                }
            }
            JsonNode shown = null;
            for (int step = 0; step < 8 && shown == null; step++) {
                JsonNode s = party.screen.state(x ->
                        List.of("VOTING", "REVEAL").contains(x.path("phase").asString()));
                if (s.path("round").path("options").toString().contains("won't show")) {
                    shown = s;
                } else {
                    String key = s.path("phase").asString()
                            + s.path("round").path("duelIndex").asInt();
                    party.screen.ok("game.next", Map.of());
                    party.screen.state(x -> !(x.path("phase").asString()
                                    + x.path("round").path("duelIndex").asInt())
                            .equals(key));
                }
            }
            assertThat(shown).as("the blocked answer is replaced on screen").isNotNull();
            assertThat(shown.path("phase").asString())
                    .as("a blocked answer loses automatically")
                    .isEqualTo("REVEAL");
            assertThat(String.join("\n", party.screen.rawFrames())).doesNotContain("BANNED joke");
            assertThat(jdbc.sql("SELECT count(*) FROM moderation_event WHERE stage = 'ANSWER' AND category = 'model'")
                            .query(Integer.class)
                            .single())
                    .isPositive();
        }
    }

    @Test
    void votingRulesAndLineSkipping() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            answerEverything(party, "Answer");
            JsonNode voting = party.screen.phase("VOTING");
            assertThat(voting.path("round").path("prompt").asString()).startsWith("AI prompt");
            Party.Phone voter = null;
            Party.Phone inDuel = null;
            for (Party.Phone p : party.phones) {
                JsonNode st =
                        p.socket().state(s -> "VOTING".equals(s.path("phase").asString()));
                if (st.path("you").path("canVote").asBoolean()) {
                    voter = p;
                } else {
                    inDuel = p;
                    assertThat(st.path("you").path("inDuel").asBoolean()).isTrue();
                }
            }
            assertThat(inDuel.socket().error("vote.submit", Map.of("optionId", "A")))
                    .isEqualTo("NOT_ALLOWED");
            assertThat(voter.socket().error("vote.submit", Map.of("optionId", "C")))
                    .isEqualTo("VALIDATION_FAILED");
            voter.socket().ok("vote.submit", Map.of("optionId", "B"));

            JsonNode reveal = party.screen.phase("REVEAL");
            assertThat(reveal.path("host").path("text").asString()).startsWith("AI reaction to");
            String lineId = reveal.path("host").path("lineId").asString();
            assertThat(voter.socket().error("vote.submit", Map.of("optionId", "A")))
                    .isEqualTo("INVALID_PHASE");
            assertThat(voter.socket().error("line.skip", Map.of("lineId", lineId)))
                    .isEqualTo("NOT_ALLOWED");
            Party.Phone subject = inDuel;
            JsonNode own =
                    subject.socket().state(s -> s.path("host").path("canSkip").asBoolean());
            assertThat(own.path("you").path("roundPoints").isNumber()).isTrue();
            subject.socket().ok("line.skip", Map.of("lineId", lineId));
            JsonNode skipped =
                    party.screen.state(s -> s.path("host").path("skipped").asBoolean());
            assertThat(skipped.path("host").path("text").asString())
                    .isEqualTo("This line was skipped at a player's request.");
            assertThat(skipped.path("host").path("audioId").isMissingNode()).isTrue();
            Await.until(
                    "skip recorded",
                    () -> jdbc.sql("SELECT count(*) FROM moderation_event WHERE action = 'SKIPPED_BY_PLAYER'")
                                    .query(Integer.class)
                                    .single()
                            > 0);
        }
    }

    @Test
    void nobodyVotingForTheRoundKindLetsTheHostPickTheLeastPlayed() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            answerEverything(party, "Answer");
            for (int duel = 0; duel < 3; duel++) {
                party.screen.phase("VOTING");
                party.screen.ok("game.next", Map.of());
                party.screen.phase("REVEAL");
                party.screen.ok("game.next", Map.of());
            }
            party.screen.phase("ROUND_VOTE");
            party.screen.ok("game.next", Map.of());
            JsonNode next = party.screen.phase("VOTING");
            assertThat(next.path("round").path("kind").asString()).isIn("WHO_OF_US", "TRUTH_OR_AI");
            assertThat(next.path("round").path("n").asInt()).isEqualTo(2);
        }
    }

    @Test
    void aRoomOverItsAiBudgetFallsBackWithoutStopping() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.captain().socket().ok("game.start", Map.of());
            party.screen.phase("INTAKE");
            RoomState room = room(party);
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
                    .get(0)
                    .socket()
                    .state(s -> s.path("you").path("assignments").size() == 2);
            phone.path("you")
                    .path("assignments")
                    .forEach(a -> assertThat(a.path("prompt").asString()).doesNotStartWith("AI prompt"));
        }
    }

    @Test
    void streamViewersVoteOnceAndSeeOnlyTheirView() {
        try (Party party = Party.create(
                port,
                json,
                FAKE,
                Map.of("tone", "FAMILY", "length", "SHORT", "mode", "STREAMER", "hideCode", true),
                3)) {
            String key = client().get("/api/rooms/" + party.code)
                    .json()
                    .path("audienceKey")
                    .asString();
            String token = client().post("/api/audiences/" + key + "/viewers", Map.of())
                    .json()
                    .path("audienceToken")
                    .asString();
            try (GameSocket viewer = GameSocket.connect(port, json, token)) {
                JsonNode first = viewer.latest();
                assertThat(first.path("code").isMissingNode()).as("hidden code").isTrue();
                assertThat(first.path("players").isMissingNode()).isTrue();
                assertThat(first.path("you").path("role").asString()).isEqualTo("AUDIENCE");
                assertThat(party.screen
                                .state(s ->
                                        s.path("lobby").path("audienceCount").asInt() == 1)
                                .path("code")
                                .asString())
                        .isEqualTo(party.code);
                assertThat(viewer.error("audience.vote", Map.of("optionId", "A")))
                        .isEqualTo("INVALID_PHASE");
                assertThat(viewer.error("vote.submit", Map.of("optionId", "A"))).isEqualTo("NOT_ALLOWED");

                toAnswering(party);
                answerEverything(party, "Answer");
                party.screen.phase("VOTING");
                viewer.ok("audience.vote", Map.of("optionId", "A"));
                viewer.ok("audience.vote", Map.of("optionId", "B"));
                JsonNode counted = party.screen.state(
                        s -> s.path("round").path("audienceTotal").asInt() == 1);
                assertThat(counted.path("round")
                                .path("options")
                                .get(0)
                                .path("audienceVotes")
                                .asInt())
                        .isEqualTo(1);
                assertThat(viewer.state(s -> s.path("audience").path("voted").asBoolean()))
                        .isNotNull();
            }
        }
    }

    @Test
    void idleRoomsCloseAndTellEveryone() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            toAnswering(party);
            clock.advance(Duration.ofMinutes(31));
            engine.sweep();
            Await.until("closed notice", () -> party.phones.get(0).socket().received("closed"));
            Await.until("screen told too", () -> party.screen.received("closed"));
            assertThat(party.host.get("/api/rooms/" + party.code).errorCode()).isEqualTo("ROOM_NOT_FOUND");
            Await.until(
                    "session ended",
                    () -> jdbc.sql("SELECT count(*) FROM game_session WHERE room_code = ? AND end_reason = 'IDLE'")
                                    .param(party.code)
                                    .query(Integer.class)
                                    .single()
                            == 1);
            boolean dossierWiped = room(party).call(r -> r.getDossier().isEmpty());
            assertThat(dossierWiped).isTrue();
            clock.advance(Duration.ofMinutes(3));
            engine.sweep();
            assertThat(registry.find(party.code)).isEmpty();
        }
    }

    @Test
    void ownerClosesTheRoom() {
        try (Party party = Party.create(port, json, FAKE, 3)) {
            party.screen.ok("room.close", Map.of());
            Await.until("closed", () -> party.phones.get(1).socket().received("closed"));
            Await.until("owner closed", () -> party.screen.received("closed"));
            assertThat(party.screen.latest().path("phase").asString()).isEqualTo("CLOSED");
            GameSocket late = new GameSocket(port, json);
            assertThat(late.error("hello", Map.of("token", party.phones.get(0).token())))
                    .isEqualTo("ROOM_NOT_FOUND");
            late.abort();
        }
    }
}
