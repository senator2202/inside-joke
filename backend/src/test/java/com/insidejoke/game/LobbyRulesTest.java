package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.settings.AppSettingsService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The lobby: joining, the captain, settings, starting a game and the paywall (user flow S1, P1, P2, H4). */
class LobbyRulesTest {

    private final GameHarness h = new GameHarness();

    @Test
    void theFirstPlayerInIsCaptainAndAGameNeedsThreePlayers() {
        GameHarness.Party party = h.party(2);
        assertThat(party.captain()).isEqualTo(party.seat(0));
        assertThat(h.send(party, party.captain(), "game.start").error()).isEqualTo(ErrorCode.NOT_ENOUGH_PLAYERS);

        h.join(party, "Cat");
        h.start(party);

        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        GameAccessPort.StartParams start = h.access.starts.getFirst();
        assertThat(start.hostUserId()).isEqualTo(GameHarness.HOST);
        assertThat(start.playerCount()).isEqualTo(3);
        assertThat(start.roomCode()).isEqualTo(party.room().getCode());
        assertThat(start.previousSessionId()).isNull();
    }

    @Test
    void namesMustBeShortPrintableAndUnique() {
        GameHarness.Party party = h.party(1);
        assertThat(h.joinError(party.room(), "")).isEqualTo(ErrorCode.NAME_INVALID);
        assertThat(h.joinError(party.room(), "!!!")).isEqualTo(ErrorCode.NAME_INVALID);
        assertThat(h.joinError(party.room(), "Bartholomew13")).isEqualTo(ErrorCode.NAME_INVALID);
        assertThat(h.joinError(party.room(), "  ann ")).isEqualTo(ErrorCode.NAME_TAKEN);
        assertThat(h.join(party, "Bartholomew1").name()).isEqualTo("Bartholomew1");
    }

    @Test
    void anEmojiFromTheListIsKeptAndAnythingElseReplaced() {
        GameHarness.Party party = h.party(0);
        String kept = h.engine.joinPlayer(party.room(), "Ann", "🐙").playerId();
        String replaced = h.engine.joinPlayer(party.room(), "Ben", "💩").playerId();
        assertThat(h.read(
                        party.room(), (RoomState r) -> r.getPlayers().get(kept).getEmoji()))
                .isEqualTo("🐙");
        assertThat(h.read(
                        party.room(),
                        (RoomState r) -> r.getPlayers().get(replaced).getEmoji()))
                .isIn(GameEngineService.EMOJIS);
    }

    @Test
    void theRoomHoldsEightAndTheOwnerCanCloseTheDoor() {
        GameHarness.Party party = h.party(7);
        h.owner(party, "room.lock", Map.of("locked", true));
        assertThat(h.joinError(party.room(), "Hal")).isEqualTo(ErrorCode.ROOM_LOCKED);
        h.owner(party, "room.lock", Map.of("locked", false));
        h.join(party, "Hal");
        assertThat(h.joinError(party.room(), "Ivy")).isEqualTo(ErrorCode.ROOM_FULL);
    }

    @Test
    void latecomersJoinDuringTheIntakeButNotOnceTheRoundsBegin() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        GameHarness.Seat late = h.join(party, "Dan");
        assertThat(h.player(party, late).getIntakeGame())
                .as("still owes the intake")
                .isZero();

        h.fillIntake(party);
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.joinError(party.room(), "Eve")).isEqualTo(ErrorCode.ROOM_IN_PROGRESS);
    }

    @Test
    void theOwnerHandsTheCrownOver() {
        GameHarness.Party party = h.party(3);
        h.owner(party, "captain.set", Map.of("playerId", party.seat(2).id()));
        assertThat(party.captain()).isEqualTo(party.seat(2));
        assertThat(h.view(party, party.seat(0)).you().role()).isEqualTo(Role.PLAYER);
        assertThat(h.view(party, party.seat(2)).you().role()).isEqualTo(Role.CAPTAIN);
        assertThat(h.send(party.room(), party.owner(), "captain.set", Map.of("playerId", "p99"))
                        .error())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void whenTheCaptainIsGoneTheCrownPassesToAPresentPlayer() {
        GameHarness.Party party = h.party(3);
        h.disconnect(party.room(), party.seat(0).member());
        h.advance(Duration.ofSeconds(19));
        assertThat(party.captain()).as("20 s grace").isEqualTo(party.seat(0));

        h.advance(Duration.ofSeconds(2));
        assertThat(party.captain()).isEqualTo(party.seat(1));
    }

    @Test
    void aKickedPlayerLeavesForGoodAndTheCaptainIsReplaced() {
        GameHarness.Party party = h.party(4);
        GameHarness.Seat captain = party.captain();
        h.owner(party, "player.kick", Map.of("playerId", captain.id()));

        assertThat(h.events.kicked).containsExactly(captain.id());
        assertThat(h.read(party.room(), (RoomState r) -> r.activePlayers().size()))
                .isEqualTo(3);
        assertThat(party.captain()).isNotEqualTo(captain);
        assertThat(h.engine.resolve(captain.token()))
                .as("the token no longer opens the room")
                .isEmpty();
        assertThat(h.send(party, captain, "intake.submit", Map.of("answers", List.of("a", "b", "c")))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
    }

    @Test
    void settingsChangeOnlyInTheLobbyAndSpicyNeedsEveryoneOverEighteen() {
        GameHarness.Party party = h.party(3);
        ApiException unconfirmed = h.send(party.room(), party.owner(), "game.settings", Map.of("tone", "SPICY"))
                .exception();
        assertThat(unconfirmed.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(unconfirmed.details()).containsKey("fields");

        h.owner(party, "game.settings", Map.of("tone", "SPICY", "length", "LONG", "adultsConfirmed", true));
        RoomSettings settings = h.read(party.room(), RoomState::getSettings);
        assertThat(settings.tone()).isEqualTo(Tone.SPICY);
        assertThat(settings.length()).isEqualTo(GameLength.LONG);
        assertThat(h.send(party.room(), party.owner(), "game.settings", Map.of("language", "de"))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);

        h.toAnswering(party);
        assertThat(h.read(party.room(), RoomState::getRoundsTotal))
                .as("a long game")
                .isEqualTo(10);
        assertThat(h.send(party.room(), party.owner(), "game.settings", Map.of("length", "SHORT"))
                        .error())
                .isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void switchingTheLanguageAsksTheQuestionsInThatLanguage() {
        GameHarness.Party party = h.party(3);
        List<String> english = h.read(party.room(), RoomState::getIntakeQuestions);
        h.owner(party, "game.settings", Map.of("language", "ru"));
        List<String> russian = h.read(party.room(), RoomState::getIntakeQuestions);
        assertThat(h.read(party.room(), (RoomState r) -> r.getSettings().language()))
                .isEqualTo(Language.RU);
        assertThat(russian).hasSize(3).isNotEqualTo(english);
        assertThat(String.join(" ", russian)).containsPattern("\\p{IsCyrillic}");
    }

    @Test
    void aHiddenCodeIsOnlyForStreamers() {
        GameHarness.Party party = h.party(3);
        h.owner(party, "game.settings", Map.of("hideCode", true));
        assertThat(h.read(party.room(), (RoomState r) -> r.getSettings().hideCode()))
                .isFalse();
    }

    @Test
    void aRefusedStartShowsThePaywallToTheOwnerAndCaptainAndKeepsEveryoneInTheLobby() {
        GameHarness.Party party = h.party(3);
        h.access.refusal = ErrorCode.PAYWALL_FREE_LIMIT;
        h.start(party);

        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);
        assertThat(h.read(party.room(), RoomState::isStarting)).isFalse();
        assertThat(h.view(party.room(), party.owner()).paywall()).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
        assertThat(h.view(party, party.captain()).paywall()).isEqualTo(ErrorCode.PAYWALL_FREE_LIMIT);
        assertThat(h.view(party, party.seat(1)).paywall()).isNull();
        verify(h.analytics).track(eq("paywall_shown"), anyString(), eq(Map.of("reason", "PAYWALL_FREE_LIMIT")));

        h.owner(party, "paywall.dismiss");
        assertThat(h.view(party.room(), party.owner()).paywall()).isNull();

        h.access.refusal = null;
        h.start(party);
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        verify(h.analytics).track(eq("game_started"), anyString(), any());
    }

    @Test
    void aSecondStartWhileTheFirstIsCheckedIsIgnored() {
        GameHarness.Party party = h.party(3);
        h.hold();
        h.start(party);
        h.start(party);
        h.release();
        assertThat(h.access.starts).hasSize(1);
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
    }

    @Test
    void drainModeRefusesNewRooms() {
        h.snapshot = new AppSettingsService.Snapshot(true, true, 5_000_000, 2000, true);
        assertThatThrownBy(() -> h.engine.createRoom(GameHarness.HOST, GameHarness.SETTINGS))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.DRAIN_MODE));
    }

    @Test
    void aNewRoomClosesTheHostsEmptyRoomButNotOneWithPlayers() {
        GameHarness.Party empty = h.party(0);
        GameHarness.Party busy = h.party(2);
        h.party(0);
        assertThat(h.phase(empty)).isEqualTo(Phase.CLOSED);
        assertThat(h.phase(busy)).isEqualTo(Phase.LOBBY);
        assertThat(h.engine.find(empty.room().getCode())).isEmpty();
    }

    @Test
    void theJoinScreenKnowsWhetherTheRoomTakesPlayers() {
        GameHarness.Party party = h.party(3);
        assertThat(h.engine.status(party.room()).joinable()).isTrue();
        assertThat(h.engine.status(party.room()).players()).isEqualTo(3);

        h.owner(party, "room.lock", Map.of("locked", true));
        assertThat(h.engine.status(party.room()).joinable()).isFalse();
        h.owner(party, "room.lock", Map.of("locked", false));

        h.toAnswering(party);
        assertThat(h.engine.status(party.room()).joinable())
                .as("the rounds have begun")
                .isFalse();
        assertThat(h.engine.status(party.room()).audienceKey())
                .as("not a streamer room")
                .isNull();
    }

    @Test
    void whenBillingFailsTheStartButtonWorksAgain() {
        GameHarness.Party party = h.party(3);
        h.access.failure = new IllegalStateException("database down");
        h.start(party);
        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);
        assertThat(h.read(party.room(), RoomState::isStarting)).isFalse();
        assertThat(h.view(party.room(), party.owner()).paywall()).isNull();

        h.access.failure = null;
        h.start(party);
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
    }
}
