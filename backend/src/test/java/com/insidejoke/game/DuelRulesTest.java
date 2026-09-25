package com.insidejoke.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The answer duel (user flow S4-S6, P6-P8): two players answer one prompt, everyone else votes for the funnier answer
 * without knowing who wrote it, 100 points a vote, a wipeout bonus, and an automatic win over a missing answer.
 */
class DuelRulesTest {

    private final GameHarness h = new GameHarness();

    /** Four players have answered; the first duel is being voted on by the two outside it. */
    private GameHarness.Party atFirstVote() {
        GameHarness.Party party = h.party(4);
        h.toAnswering(party);
        h.answerAll(party);
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
        return party;
    }

    private DuelState current(GameHarness.Party party) {
        return h.read(party.room(), (RoomState r) -> r.getRound().currentDuel());
    }

    private GameHarness.Answer vote(GameHarness.Party party, GameHarness.Seat seat, String option) {
        return h.send(party, seat, "vote.submit", Map.of("optionId", option));
    }

    @Test
    void everyPlayerWritesForTwoDuelsAgainstDifferentOpponents() {
        GameHarness.Party party = h.party(5);
        h.toAnswering(party);

        List<DuelState> duels = h.round(party).getDuels();
        assertThat(duels).hasSize(5);
        for (GameHarness.Seat seat : party.seats()) {
            List<DuelState> mine = h.duelsOf(party, seat);
            assertThat(mine).hasSize(2);
            assertThat(mine.stream().map(d -> d.getPlayerA().equals(seat.id()) ? d.getPlayerB() : d.getPlayerA()))
                    .doesNotHaveDuplicates()
                    .doesNotContain(seat.id());
            assertThat(h.view(party, seat).you().assignments()).hasSize(2);
        }
        assertThat(duels).extracting(DuelState::getPrompt).doesNotHaveDuplicates();
        assertThat(h.read(party.room(), RoomState::getDeadlineMs)).isEqualTo(h.clock.millis() + 60_000);
    }

    @Test
    void answersAreCheckedBeforeTheyCount() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        GameHarness.Seat p = party.seat(0);
        String mine = h.duelsOf(party, p).getFirst().getId();
        String notMine = h.round(party).getDuels().stream()
                .filter(d -> !d.involves(p.id()))
                .findFirst()
                .orElseThrow()
                .getId();

        assertThat(h.send(party, p, "answer.submit", Map.of("duelId", notMine, "text", "Hi"))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(h.send(party, p, "answer.submit", Map.of("duelId", mine, "text", " "))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(h.send(party, p, "answer.submit", Map.of("duelId", mine, "text", "x".repeat(121)))
                        .error())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(h.send(party, p, "answer.submit", Map.of("duelId", mine, "text", "see bit.ly/xyz"))
                        .error())
                .isEqualTo(ErrorCode.MODERATION_BLOCKED);
        verify(h.moderation)
                .insert(any(), eq(ModerationStage.ANSWER), anyString(), eq(ModerationAction.BLOCKED), any());

        h.send(party, p, "answer.submit", Map.of("duelId", mine, "text", "First try"))
                .ok();
        h.send(party, p, "answer.submit", Map.of("duelId", mine, "text", "Better answer"))
                .ok();
        assertThat(h.duelsOf(party, p).getFirst().answerOf(p.id())).isEqualTo("Better answer");
        assertThat(h.player(party, p).getAnswersGiven())
                .as("a rewrite is still one answer")
                .isEqualTo(1);
    }

    @Test
    void onceEveryoneHasAnsweredVotingStartsWithoutTheDuellists() {
        GameHarness.Party party = atFirstVote();
        DuelState duel = current(party);

        assertThat(h.round(party).getDuelIndex()).isZero();
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().text()))
                .isEqualTo(duel.getPrompt());
        assertThat(h.voters(party))
                .extracting(GameHarness.Seat::id)
                .containsExactlyInAnyOrderElementsOf(party.seats().stream()
                        .map(GameHarness.Seat::id)
                        .filter(id -> !duel.involves(id))
                        .toList());
        GameHarness.Seat duellist = party.byId(duel.getPlayerA());
        assertThat(h.view(party, duellist).you().inDuel()).isTrue();
        assertThat(h.view(party, duellist).you().canVote()).isFalse();
        assertThat(vote(party, duellist, "A").error()).isEqualTo(ErrorCode.NOT_ALLOWED);
    }

    @Test
    void eachVoteIsWorthAHundredAndAWipeoutAddsTwoHundredFifty() {
        GameHarness.Party party = atFirstVote();
        DuelState duel = current(party);
        List<GameHarness.Seat> voters = h.voters(party);

        vote(party, voters.getFirst(), "A").ok();
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
        vote(party, voters.get(1), "A").ok();

        assertThat(h.phase(party)).as("everyone voted").isEqualTo(Phase.REVEAL);
        assertThat(h.score(party, party.byId(duel.getPlayerA()))).isEqualTo(2 * 100 + 250);
        assertThat(h.score(party, party.byId(duel.getPlayerB()))).isZero();
        assertThat(h.player(party, party.byId(duel.getPlayerA())).getDuelsWon()).isEqualTo(1);
        assertThat(h.view(party.room(), party.owner()).round().landslide()).isTrue();
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().text()))
                .as("the model's reaction")
                .isEqualTo("AI reaction to " + duel.getId());
        assertThat(h.read(party.room(), (RoomState r) -> r.getHostLine().aboutPlayerIds()))
                .containsExactlyInAnyOrder(duel.getPlayerA(), duel.getPlayerB());
    }

    @Test
    void aSplitVoteGivesEachAuthorTheirVotesAndNoWinner() {
        GameHarness.Party party = atFirstVote();
        DuelState duel = current(party);
        List<GameHarness.Seat> voters = h.voters(party);
        vote(party, voters.getFirst(), "A").ok();
        vote(party, voters.get(1), "B").ok();

        assertThat(h.score(party, party.byId(duel.getPlayerA()))).isEqualTo(100);
        assertThat(h.score(party, party.byId(duel.getPlayerB()))).isEqualTo(100);
        assertThat(h.player(party, party.byId(duel.getPlayerA())).getDuelsWon()).isZero();
        assertThat(h.view(party.room(), party.owner()).round().landslide()).isFalse();
    }

    @Test
    void aVoteIsFinalAndOnlyForTheOptionsShown() {
        GameHarness.Party party = atFirstVote();
        List<GameHarness.Seat> voters = h.voters(party);
        assertThat(vote(party, voters.getFirst(), "C").error()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        vote(party, voters.getFirst(), "B").ok();
        assertThat(vote(party, voters.getFirst(), "A").error()).isEqualTo(ErrorCode.INVALID_PHASE);
        assertThat(h.view(party, voters.getFirst()).you().myVote()).isEqualTo("B");

        vote(party, voters.get(1), "B").ok();
        assertThat(vote(party, voters.get(1), "A").error())
                .as("the reveal is on")
                .isEqualTo(ErrorCode.INVALID_PHASE);
    }

    @Test
    void votingLastsTwentySecondsAndTheRevealTwelve() {
        GameHarness.Party party = atFirstVote();
        h.advance(Duration.ofSeconds(19));
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
        h.advance(Duration.ofSeconds(2));
        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);

        h.advance(Duration.ofSeconds(11));
        assertThat(h.phase(party)).isEqualTo(Phase.REVEAL);
        h.advance(Duration.ofSeconds(2));
        assertThat(h.phase(party)).isEqualTo(Phase.VOTING);
        assertThat(h.round(party).getDuelIndex()).isEqualTo(1);
    }

    @Test
    void whoeverFacesAMissingAnswerWinsAHundredPerOtherPlayer() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        GameHarness.Seat silent = party.seat(2);
        for (GameHarness.Seat seat : List.of(party.seat(0), party.seat(1))) {
            for (DuelState d : h.duelsOf(party, seat)) {
                h.send(party, seat, "answer.submit", Map.of("duelId", d.getId(), "text", "Answer"))
                        .ok();
            }
        }
        assertThat(h.phase(party)).as("still waiting for the third player").isEqualTo(Phase.ANSWERING);
        h.advance(Duration.ofSeconds(61));

        h.skipTo(party, Phase.ROUND_VOTE);
        assertThat(h.score(party, silent)).isZero();
        for (GameHarness.Seat seat : List.of(party.seat(0), party.seat(1))) {
            assertThat(h.score(party, seat))
                    .as("one walkover, one duel nobody voted on")
                    .isEqualTo(100);
            assertThat(h.player(party, seat).getDuelsWon()).isEqualTo(1);
        }
    }

    @Test
    void theModelCanHideAnAnswerWhichThenLosesAndIsNeverShown() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        GameHarness.Seat rude = party.seat(0);
        DuelState target = h.duelsOf(party, rude).getFirst();
        h.send(party, rude, "answer.submit", Map.of("duelId", target.getId(), "text", "BANNED joke"))
                .ok();
        h.answerAll(party);

        Map<String, String> seen = new HashMap<>();
        for (int i = 0; i < 20 && h.phase(party) != Phase.ROUND_VOTE; i++) {
            seen.put(h.phase(party) + "" + h.round(party).getDuelIndex(), h.allViews(party.room()));
            h.owner(party, "game.next");
        }
        assertThat(seen.values()).noneMatch(views -> views.contains("BANNED joke"));
        assertThat(String.join("", seen.values())).contains("The host won't show this answer");

        boolean rudeIsA = target.getPlayerA().equals(rude.id());
        String opponent = rudeIsA ? target.getPlayerB() : target.getPlayerA();
        assertThat(rudeIsA ? target.getPointsB() : target.getPointsA())
                .as("the opponent wins the duel")
                .isEqualTo(100);
        assertThat(h.player(party, party.byId(opponent)).getDuelsWon()).isPositive();
        verify(h.moderation, atLeastOnce())
                .insert(any(), eq(ModerationStage.ANSWER), eq("model"), eq(ModerationAction.BLOCKED), any());
    }

    @Test
    void theMostVotedAnswerBecomesTheAnswerOfTheNight() {
        GameHarness.Party party = atFirstVote();
        DuelState duel = current(party);
        h.voters(party).forEach(v -> vote(party, v, "B").ok());

        GameHarness.Seat author = party.byId(duel.getPlayerB());
        assertThat(h.read(party.room(), RoomState::getAnswerOfNightText))
                .isEqualTo(GameHarness.answerText(author, duel));
        assertThat(h.read(party.room(), RoomState::getAnswerOfNightAuthor)).isEqualTo(author.id());
        assertThat(h.player(party, author).getVotesReceived()).isEqualTo(2);
    }

    @Test
    void whenNobodyAnswersTheRoundEndsWithoutVoting() {
        GameHarness.Party party = h.party(3);
        h.toAnswering(party);
        h.expire(party.room());
        assertThat(h.phase(party)).isEqualTo(Phase.ROUND_VOTE);
        assertThat(h.read(party.room(), RoomState::getRoundNumber)).isEqualTo(2);
        assertThat(party.seats()).allMatch(s -> h.score(party, s) == 0);
    }
}
