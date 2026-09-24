package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The two rounds without written answers (user flow S5, P7): "Who of us" (a vote for a player, oneself included) and
 * "Truth or AI" (a real secret or the host's invention; the player it is about keeps a poker face).
 */
class WhoOfUsAndTruthRulesTest {

    private final GameHarness h = new GameHarness();

    /** Round 1 is played and the party voted for {@code kind}: its vote is open. */
    private GameHarness.Party inRoundTwo(GameHarness.Party party, RoundKind kind) {
        h.toAnswering(party);
        h.skipTo(party, Phase.ROUND_VOTE);
        party.seats()
                .forEach(s -> h.send(party, s, "round.kind.vote", Map.of("kind", kind.name()))
                        .ok());
        h.owner(party, "game.next");
        assertThat(h.round(party).getKind()).isEqualTo(kind);
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
        return party;
    }

    private GameHarness.Answer vote(GameHarness.Party party, GameHarness.Seat seat, String option) {
        return h.send(party, seat, "vote.submit", Map.of("optionId", option));
    }

    private Map<String, Integer> roundPoints(GameHarness.Party party) {
        return h.read(party.room(), (RoomState r) -> Map.copyOf(r.getRound().getPoints()));
    }

    // ---------------------------------------------------------------- who of us

    @Test
    void whoOfUsAsksTheQuestionAndEveryoneMayVoteForAnyoneIncludingThemselves() {
        GameHarness.Party party = inRoundTwo(h.party(3), RoundKind.WHO_OF_US);
        assertThat(h.round(party).getQuestion()).isEqualTo("AI question for round 2");
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().text()))
                .isEqualTo("AI question for round 2");
        assertThat(h.voters(party)).containsExactlyElementsOf(party.seats());
        assertThat(h.view(party.room(), party.owner()).round().options())
                .extracting(o -> o.playerId())
                .containsExactlyElementsOf(
                        party.seats().stream().map(GameHarness.Seat::id).toList());

        vote(party, party.seat(0), party.seat(0).id()).ok();
        assertThat(vote(party, party.seat(1), "nobody").error()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void theMostNamedGetTwoHundredAndWhoeverPickedThemAHundred() {
        GameHarness.Party party = inRoundTwo(h.party(4), RoundKind.WHO_OF_US);
        GameHarness.Seat ann = party.seat(0);
        GameHarness.Seat ben = party.seat(1);
        GameHarness.Seat cat = party.seat(2);
        GameHarness.Seat dan = party.seat(3);
        vote(party, ann, ben.id()).ok();
        vote(party, ben, ann.id()).ok();
        vote(party, cat, ann.id()).ok();
        vote(party, dan, ben.id()).ok();

        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        assertThat(roundPoints(party))
                .as("a tie: both are named twice")
                .containsExactlyInAnyOrderEntriesOf(Map.of(ann.id(), 300, ben.id(), 300, cat.id(), 100, dan.id(), 100));
        assertThat(h.player(party, ann).getWhoPicks()).isEqualTo(1);
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().aboutPlayerIds()))
                .containsExactlyInAnyOrder(ann.id(), ben.id());
    }

    @Test
    void whenNobodyVotesNobodyScores() {
        GameHarness.Party party = inRoundTwo(h.party(3), RoundKind.WHO_OF_US);
        h.expire(party.room());
        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        assertThat(roundPoints(party)).isEmpty();
    }

    // ---------------------------------------------------------------- truth or AI

    @Test
    void withoutSecretsTheStatementIsTheHostsInvention() {
        GameHarness.Party party = inRoundTwo(h.party(3), RoundKind.TRUTH_OR_AI);
        RoundState round = h.round(party);
        assertThat(round.isStatementTrue()).isFalse();
        assertThat(round.getStatement()).startsWith("AI invention about");
    }

    @Test
    void aSecretBecomesTheStatementAboutThePlayerItIsAbout() {
        GameHarness.Party party = h.party(4);
        GameHarness.Seat dan = party.seat(3);
        h.send(party, party.seat(0), "dossier.add", Map.of("aboutPlayerId", dan.id(), "text", "Owns forty cacti"))
                .ok();
        inRoundTwo(party, RoundKind.TRUTH_OR_AI);

        RoundState round = h.round(party);
        assertThat(round.getSubjectId()).isEqualTo(dan.id());
        assertThat(round.getStatement())
                .isEqualTo(round.isStatementTrue() ? "Restated: Owns forty cacti" : "AI invention for round 2");
        assertThat(h.view(party, dan).you().subject()).isTrue();
        assertThat(h.voters(party)).as("the subject knows the answer").doesNotContain(dan);
        assertThat(vote(party, dan, "truth").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
    }

    @Test
    void aRightGuessScoresTwoHundredAndEveryoneFooledGivesTheSubjectAHundred() {
        GameHarness.Party party = h.party(4);
        GameHarness.Seat dan = party.seat(3);
        h.send(party, party.seat(0), "dossier.add", Map.of("aboutPlayerId", dan.id(), "text", "Owns forty cacti"))
                .ok();
        inRoundTwo(party, RoundKind.TRUTH_OR_AI);
        String right = h.round(party).isStatementTrue() ? "truth" : "ai";
        String wrong = right.equals("truth") ? "ai" : "truth";

        vote(party, party.seat(0), right).ok();
        vote(party, party.seat(1), right).ok();
        vote(party, party.seat(2), wrong).ok();

        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        assertThat(roundPoints(party))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(party.seat(0).id(), 200, party.seat(1).id(), 200, dan.id(), 100));
        assertThat(h.player(party, party.seat(0)).getCorrectGuesses()).isEqualTo(1);
        assertThat(h.player(party, dan).getPeopleFooled()).isEqualTo(1);
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().text()))
                .isIn(List.of("AI truth line", "AI fake line"));
    }
}
