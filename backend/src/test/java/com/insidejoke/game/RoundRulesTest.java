package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.ErrorCode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Choosing the next round kind (15 s, S3 and P5) and getting its content: prepared a round ahead by the AI, or
 * prewritten when the AI is late or down (blueprint 4.4, user flow section 1 "the host never makes you wait").
 */
class RoundRulesTest {

    private final GameHarness h = new GameHarness();

    /** Round 1 is played; the party is choosing the kind of round 2. */
    private GameHarness.Party atRoundTwoVote(int players) {
        GameHarness.Party party = h.party(players);
        h.toAnswering(party);
        h.skipTo(party, Phase.ROUND_VOTE);
        return party;
    }

    private void vote(GameHarness.Party party, GameHarness.Seat seat, RoundKind kind) {
        h.send(party, seat, "round.kind.vote", Map.of("kind", kind.name())).ok();
    }

    @Test
    void aShortGameHasFiveRoundsAndALongOneTen() {
        GameHarness.Party shortGame = h.party(3);
        h.start(shortGame);
        assertThat(h.read(shortGame.room(), RoomState::getRoundsTotal)).isEqualTo(5);

        GameHarness.Party longGame =
                h.party(3, new RoomSettings(Tone.FAMILY, GameLength.LONG, RoomMode.STANDARD, false, null));
        h.start(longGame);
        assertThat(h.read(longGame.room(), RoomState::getRoundsTotal)).isEqualTo(10);
    }

    @Test
    void fromTheSecondRoundThePartyVotesForFifteenSeconds() {
        GameHarness.Party party = atRoundTwoVote(3);
        assertThat(h.read(party.room(), RoomState::getRoundNumber)).isEqualTo(2);
        assertThat(h.read(party.room(), RoomState::getDeadlineMs)).isEqualTo(h.clock.millis() + 15_000);

        vote(party, party.seat(0), RoundKind.WHO_OF_US);
        vote(party, party.seat(1), RoundKind.WHO_OF_US);
        vote(party, party.seat(2), RoundKind.TRUTH_OR_AI);
        assertThat(h.view(party.room(), party.owner()).kindVote().voters().get(RoundKind.WHO_OF_US))
                .containsExactly(party.seat(0).id(), party.seat(1).id());

        h.advance(Duration.ofSeconds(14));
        assertThat(h.phase(party)).isEqualTo(Phase.ROUND_VOTE);
        h.advance(Duration.ofSeconds(2));
        assertThat(h.round(party).getKind()).isEqualTo(RoundKind.WHO_OF_US);
        assertThat(h.phase(party)).as("no answers to write").isEqualTo(Phase.VOTING);
    }

    @Test
    void aVoteCanBeChangedUntilTheTimerEnds() {
        GameHarness.Party party = atRoundTwoVote(3);
        vote(party, party.seat(0), RoundKind.WHO_OF_US);
        vote(party, party.seat(0), RoundKind.TRUTH_OR_AI);
        assertThat(h.view(party, party.seat(0)).kindVote().myKind()).isEqualTo(RoundKind.TRUTH_OR_AI);
        h.expire(party.room());
        assertThat(h.round(party).getKind()).isEqualTo(RoundKind.TRUTH_OR_AI);
    }

    @Test
    void aTieGoesToTheKindPlayedLeast() {
        GameHarness.Party party = atRoundTwoVote(3);
        vote(party, party.seat(0), RoundKind.ANSWER_DUEL);
        vote(party, party.seat(1), RoundKind.WHO_OF_US);
        h.expire(party.room());
        assertThat(h.round(party).getKind()).as("round 1 was a duel").isEqualTo(RoundKind.WHO_OF_US);
    }

    @Test
    void whenNobodyVotesTheHostPicksAKindPlayedLeast() {
        GameHarness.Party party = atRoundTwoVote(3);
        h.owner(party, "game.next");
        assertThat(h.round(party).getKind()).isIn(RoundKind.WHO_OF_US, RoundKind.TRUTH_OR_AI);
    }

    @Test
    void onlyPlayersVoteAndOnlyForAKindThatExists() {
        GameHarness.Party party = atRoundTwoVote(3);
        assertThat(h.send(party, party.seat(0), "round.kind.vote", Map.of("kind", "DANCE_OFF"))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(h.send(party.room(), party.owner(), "round.kind.vote", Map.of("kind", "WHO_OF_US"))
                        .error())
                .isEqualTo(ErrorCode.NOT_ALLOWED);
        h.owner(party, "game.next");
        assertThat(h.send(party, party.seat(0), "round.kind.vote", Map.of("kind", "WHO_OF_US"))
                        .error())
                .isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void theNextRoundIsPreparedAheadFromTheIntakeAndTheSecrets() {
        GameHarness.Party party = h.party(3);
        h.send(party, party.seat(0), "dossier.add", Map.of("text", "Owns forty cacti"))
                .ok();
        h.toAnswering(party);

        assertThat(h.ai.roundRequests)
                .extracting(HostAiService.RoundParams::roundNumber)
                .containsExactly(1, 2);
        HostAiService.RoundParams next = h.ai.roundRequests.getLast();
        assertThat(next.roundsTotal()).isEqualTo(5);
        assertThat(next.players()).hasSize(3);
        assertThat(next.players().getFirst().intakeAnswers())
                .hasSize(3)
                .allMatch(a -> a.endsWith("Pizza") || a.endsWith("Karaoke") || a.endsWith("Lost a shoe"));
        assertThat(next.dossier()).extracting(HostAiService.Fact::text).containsExactly("Owns forty cacti");
        assertThat(next.recentPrompts())
                .as("round 1's prompts are not repeated")
                .isNotEmpty();
    }

    @Test
    void aLateAiLetsTheHostThinkForFiveSecondsThenUsesPrewrittenContent() {
        GameHarness.Party party = h.party(3);
        h.start(party);
        h.hold();
        h.owner(party, "game.next");

        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        assertThat(h.view(party.room(), party.owner()).thinking()).isTrue();
        assertThat(h.view(party.room(), party.owner()).deadline())
                .as("no timer while the host thinks")
                .isNull();

        h.advance(Duration.ofMillis(4900));
        assertThat(h.phase(party)).isEqualTo(Phase.INTAKE);
        h.advance(Duration.ofMillis(200));
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.round(party).getDuels())
                .extracting(DuelState::getPrompt)
                .noneMatch(prompt -> prompt.startsWith("AI prompt"));

        h.release();
        assertThat(h.round(party).getDuels())
                .as("the late answer changes nothing")
                .extracting(DuelState::getPrompt)
                .noneMatch(prompt -> prompt.startsWith("AI prompt"));
    }

    @Test
    void anAiOutageUsesPrewrittenContentAtOnce() {
        GameHarness.Party party = h.party(3);
        h.ai.down = true;
        h.start(party);
        h.owner(party, "game.next");
        assertThat(h.phase(party)).isEqualTo(Phase.ANSWERING);
        assertThat(h.round(party).getDuels()).extracting(DuelState::getPrompt).allMatch(p -> !p.isBlank());

        h.skipTo(party, Phase.ROUND_VOTE);
        h.owner(party, "game.next");
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
    }

    @Test
    void theAiContentIsUsedWhenItIsOnTime() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        assertThat(h.round(party).getDuels())
                .extracting(DuelState::getPrompt)
                .allMatch(prompt -> prompt.startsWith("AI prompt"));
    }
}
