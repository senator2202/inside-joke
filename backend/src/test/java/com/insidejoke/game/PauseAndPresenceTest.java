package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.RoomStateDto;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pauses and people coming and going (user flow S7, blueprint 4.6): the owner's pause, the shared screen going dark,
 * a 20 s grace for dropped phones, and a 60 s wait when fewer than three players are left.
 */
class PauseAndPresenceTest {

    private final GameHarness h = new GameHarness();

    private GameHarness.Party answering() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        return party;
    }

    @Test
    void theOwnersPauseFreezesTheTimerUntilTheyContinue() {
        GameHarness.Party party = answering();
        h.advance(Duration.ofSeconds(10));
        h.owner(party, "game.pause");

        RoomStateDto paused = h.view(party.room(), party.owner());
        assertThat(paused.paused().reason()).isEqualTo(PauseReason.OWNER);
        assertThat(paused.paused().remainingMs()).isEqualTo(50_000);
        assertThat(paused.deadline()).isNull();

        h.advance(Duration.ofMinutes(5));
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);

        h.owner(party, "game.resume");
        assertThat(h.read(party.room(), RoomState::getDeadlineMs)).isEqualTo(h.clock.millis() + 50_000);
        assertThat(h.send(party.room(), party.owner(), "game.resume").error()).isEqualTo(ErrorCode.INVALID_PHASE);
        h.advance(Duration.ofSeconds(51));
        assertThat(h.phase(party)).isNotEqualTo(Phase.ANSWERING);
    }

    @Test
    void thereIsNothingToPauseOutsideAGame() {
        GameHarness.Party party = h.party(3);
        assertThat(h.send(party.room(), party.owner(), "game.pause").error()).isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void aSharedScreenGoneForTenSecondsPausesTheGameAndWelcomesItBack() {
        GameHarness.Party party = answering();
        h.disconnect(party.room(), party.owner());
        h.advance(Duration.ofSeconds(9));
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();

        h.advance(Duration.ofSeconds(2));
        assertThat(h.read(party.room(), RoomState::getPause)).isEqualTo(PauseReason.SCREEN_LOST);
        h.advance(Duration.ofMinutes(2));
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);

        h.reconnect(party.room(), party.owner());
        assertThat(h.view(party.room(), party.owner()).paused().welcomeBack()).isTrue();
        h.owner(party, "game.resume");
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();
    }

    @Test
    void aScreenBackWithinTenSecondsChangesNothing() {
        GameHarness.Party party = answering();
        h.disconnect(party.room(), party.owner());
        h.advance(Duration.ofSeconds(5));
        h.reconnect(party.room(), party.owner());
        h.advance(Duration.ofSeconds(10));
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();
    }

    @Test
    void aDroppedPhoneHasTwentySecondsBeforeTheGameWaitsForIt() {
        GameHarness.Party party = answering();
        GameHarness.Seat leaver = party.seat(2);
        h.disconnect(party.room(), leaver.member());
        h.advance(Duration.ofSeconds(19));
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();

        h.advance(Duration.ofSeconds(2));
        RoomStateDto waiting = h.view(party.room(), party.owner());
        assertThat(waiting.paused().reason()).isEqualTo(PauseReason.WAITING_FOR_PLAYERS);
        assertThat(waiting.waiting().missing()).containsExactly(leaver.name());
        assertThat(waiting.waiting().deadline() - h.clock.millis())
                .as("about a minute")
                .isBetween(59_000L, 60_000L);
    }

    @Test
    void theGameGoesOnWhenThePlayerReturns() {
        GameHarness.Party party = answering();
        GameHarness.Seat leaver = party.seat(2);
        h.disconnect(party.room(), leaver.member());
        h.advance(Duration.ofSeconds(30));
        assertThat(h.read(party.room(), RoomState::getPause)).isEqualTo(PauseReason.WAITING_FOR_PLAYERS);

        h.reconnect(party.room(), leaver.member());
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.view(party, leaver).you().assignments())
                .as("the prompts wait for them")
                .hasSize(2);
    }

    @Test
    void afterSixtySecondsWithTooFewPlayersTheGameEnds() {
        GameHarness.Party party = answering();
        h.disconnect(party.room(), party.seat(2).member());
        h.advance(Duration.ofSeconds(21));
        h.advance(Duration.ofSeconds(59));
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);

        h.advance(Duration.ofSeconds(2));
        assertThat(h.phase(party)).isEqualTo(Phase.FINALE);
        assertThat(h.view(party.room(), party.owner()).finale().standings()).hasSize(3);
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.NOT_ENOUGH_PLAYERS));
    }

    @Test
    void aKickDuringTheGameCountsTowardsTheMinimumAtOnce() {
        GameHarness.Party party = answering();
        h.owner(party, "player.kick", Map.of("playerId", party.seat(2).id()));
        assertThat(h.read(party.room(), RoomState::getPause)).isEqualTo(PauseReason.WAITING_FOR_PLAYERS);
    }

    @Test
    void theLobbyDoesNotWaitForAnyone() {
        GameHarness.Party party = h.party(3);
        h.disconnect(party.room(), party.seat(2).member());
        h.advance(Duration.ofMinutes(2));
        assertThat(h.read(party.room(), RoomState::getPause)).isNull();
        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);
    }

    @Test
    void anAbsentPlayersDuelsAreDecidedWithoutThem() {
        GameHarness.Party party = h.party(4);
        h.toAnswering(party);
        GameHarness.Seat leaver = party.seat(3);
        h.disconnect(party.room(), leaver.member());
        h.advance(Duration.ofSeconds(21));
        assertThat(h.read(party.room(), RoomState::getPause))
                .as("three are still here")
                .isNull();

        for (GameHarness.Seat seat : party.seats().subList(0, 3)) {
            for (DuelState d : h.duelsOf(party, seat)) {
                h.send(party, seat, "answer.submit", Map.of("duelId", d.getId(), "text", "Answer"))
                        .ok();
            }
        }
        assertThat(h.phase(party))
                .as("nobody waits for the absent player's answers")
                .isNotEqualTo(Phase.ANSWERING);
    }
}
