package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.dto.FinaleDto;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The finale and "Play again" (user flow S8, P9): standings, a title for everyone, the answer of the night, the closing
 * speech, and a new game with the same party where only newcomers fill the intake.
 */
class FinaleRulesTest {

    private final GameHarness h = new GameHarness();

    /**
     * Plays a short game where every "Who of us" vote names {@code favourite}: from round 2 on, the favourite gains 300
     * a round and everyone else 100.
     */
    private void playNaming(GameHarness.Party party, GameHarness.Seat favourite) {
        for (int i = 0; i < 300 && h.phase(party) != Phase.FINALE; i++) {
            RoundState round = h.round(party);
            if (h.phase(party) == Phase.ROUND_VOTE) {
                party.seats().forEach(s -> h.send(party, s, "round.kind.vote", Map.of("kind", "WHO_OF_US")));
                h.owner(party, "game.next");
            } else if (h.phase(party) == Phase.VOTING && round.getKind() == RoundKind.WHO_OF_US) {
                h.voters(party).forEach(v -> h.send(party, v, "vote.submit", Map.of("optionId", favourite.id())));
            } else if (h.phase(party) == Phase.ANSWERING) {
                h.answerAll(party);
            } else {
                h.owner(party, "game.next");
            }
        }
        assertThat(h.phase(party)).isEqualTo(Phase.FINALE);
    }

    @Test
    void theLastRevealPreparesTheFinaleAndTheModelTitlesEveryone() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.playToFinale(party, RoundKind.WHO_OF_US);

        FinaleDto finale = h.view(party.room(), party.owner()).finale();
        assertThat(finale.ready()).isTrue();
        assertThat(finale.titles())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        party.seat(0).id(), "AI title for Ann",
                        party.seat(1).id(), "AI title for Ben",
                        party.seat(2).id(), "AI title for Cat"));
        assertThat(finale.speech()).isEqualTo("AI closing speech");
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().text()))
                .isEqualTo("AI closing speech");
        assertThat(h.view(party, party.seat(1)).you().title()).isEqualTo("AI title for Ben");
        assertThat(h.ai.finaleRequests).isEqualTo(1);
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.COMPLETED && f.roundsPlayed() == 5));
    }

    @Test
    void withoutTheModelTheTitlesComeFromTheGamesStatistics() {
        GameHarness.Party party = h.party(3);
        GameHarness.Seat cat = party.seat(2);
        h.toAnswering(party);
        h.ai.down = true;
        playNaming(party, cat);

        FinaleDto finale = h.view(party.room(), party.owner()).finale();
        assertThat(finale.standings().getFirst().id()).isEqualTo(cat.id());
        assertThat(finale.standings().getFirst().score()).isEqualTo(4 * 300);
        assertThat(finale.winnerIds()).containsExactly(cat.id());
        assertThat(finale.titles().get(cat.id())).isEqualTo("The one who can't be stopped");
        assertThat(finale.titles()).hasSize(3).doesNotContainValue(null);
        assertThat(finale.speech()).contains("Cat");
        assertThat(h.read(party.room(), (RoomState r) -> r.getFinale().source()))
                .isEqualTo("FALLBACK");
    }

    @Test
    void aLateFinaleLetsTheHostThinkFiveSecondsAndTheFallbackStays() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        for (int i = 0; i < 300; i++) {
            Phase phase = h.phase(party);
            if (phase == Phase.VOTING
                    && h.read(party.room(), (RoomState r) -> r.getRoundNumber() == r.getRoundsTotal())) {
                break;
            }
            if (phase == Phase.ROUND_VOTE) {
                party.seats().forEach(s -> h.send(party, s, "round.kind.vote", Map.of("kind", "WHO_OF_US")));
                h.owner(party, "game.next");
            } else if (phase == Phase.ANSWERING) {
                h.answerAll(party);
            } else {
                h.owner(party, "game.next");
            }
        }
        h.hold();
        h.owner(party, "game.next");
        h.owner(party, "game.next");

        assertThat(h.phase(party)).isEqualTo(Phase.FINALE);
        assertThat(h.view(party.room(), party.owner()).finale().ready()).isFalse();
        assertThat(h.view(party.room(), party.owner()).thinking()).isTrue();

        h.advance(Duration.ofSeconds(6));
        assertThat(h.read(party.room(), (RoomState r) -> r.getFinale().source()))
                .isEqualTo("FALLBACK");
        String speech = h.read(party.room(), (RoomState r) -> r.getFinale().speech());

        h.release();
        assertThat(h.read(party.room(), (RoomState r) -> r.getFinale().source()))
                .as("titles already on screen don't change")
                .isEqualTo("FALLBACK");
        assertThat(h.read(party.room(), (RoomState r) -> r.getFinale().speech()))
                .isEqualTo(speech);
    }

    @Test
    void theOwnerCanEndTheGameEarly() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        assertThat(h.send(party, party.captain(), "game.end").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
        h.owner(party, "game.end");

        assertThat(h.phase(party)).isEqualTo(Phase.FINALE);
        assertThat(h.view(party.room(), party.owner()).finale().ready()).isTrue();
        verify(h.sessions).finish(any(), argThat(f -> f.reason() == EndReason.ENDED_BY_HOST && f.roundsPlayed() == 0));
        assertThat(h.send(party.room(), party.owner(), "game.end").error()).isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void playingAgainKeepsThePartyAndOnlyNewcomersFillTheIntake() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.playToFinale(party, RoundKind.WHO_OF_US);
        UUID firstGame = h.read(party.room(), RoomState::getLastSessionId);

        h.send(party, party.captain(), "game.again").ok();
        assertThat(h.phase(party)).isEqualTo(Phase.LOBBY);
        assertThat(party.seats()).allMatch(s -> h.score(party, s) == 0);

        GameHarness.Seat dan = h.join(party, "Dan");
        h.start(party);
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        assertThat(h.access.starts.getLast().previousSessionId()).isEqualTo(firstGame);
        assertThat(h.view(party, party.seat(0)).you().intakeNeeded()).isFalse();
        assertThat(h.view(party, dan).you().intakeNeeded()).isTrue();

        h.send(party, dan, "intake.submit", Map.of("answers", List.of("Tea", "Chess", "Rain")))
                .ok();
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.read(party.room(), RoomState::getRoundNumber)).isEqualTo(1);
    }

    @Test
    void theSamePartyPlayingAgainSkipsTheIntake() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.playToFinale(party, RoundKind.TRUTH_OR_AI);
        h.send(party, party.captain(), "game.again").ok();
        h.start(party);
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
    }

    @Test
    void playersWhoLeftAreLeftOutOfTheNextGame() {
        GameHarness.Party party = h.party(4);
        h.toAnswering(party);
        h.playToFinale(party, RoundKind.WHO_OF_US);
        h.disconnect(party.room(), party.seat(3).member());
        h.advance(Duration.ofSeconds(21));

        h.send(party, party.captain(), "game.again").ok();
        assertThat(h.read(party.room(), (RoomState r) -> r.activePlayers().size()))
                .isEqualTo(3);
        assertThat(h.player(party, party.seat(3)).isRemoved()).isTrue();
    }

    @Test
    void playingAgainIsOnlyForAFinishedGame() {
        GameHarness.Party party = h.party(3);
        assertThat(h.send(party, party.captain(), "game.again").error()).isEqualTo(ErrorCode.INVALID_PHASE);
    }
}
