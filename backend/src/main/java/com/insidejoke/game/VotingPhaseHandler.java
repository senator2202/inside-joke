package com.insidejoke.game;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.moderation.ContentRuleUtils;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Answering, voting, revealing results and scoring, for all three round kinds. Runs under the room lock taken by
 * {@link GameEngineService}.
 */
final class VotingPhaseHandler {

    private final GameRuntimeService runtime;
    private final GameProperties props;
    private final HostAiService ai;

    VotingPhaseHandler(GameRuntimeService runtime, GameProperties props, HostAiService ai) {
        this.runtime = runtime;
        this.props = props;
        this.ai = ai;
    }

    void submitAnswer(RoomState r, PlayerState p, String duelId, String rawText) {
        if (r.getPhase() != Phase.ANSWERING || r.getRound() == null) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
        DuelState duel = r.getRound().getDuels().stream()
                .filter(d -> d.getId().equals(duelId) && d.involves(p.getId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "That prompt isn't yours."));
        String text = ContentRuleUtils.clean(rawText);
        if (text.isEmpty() || text.codePointCount(0, text.length()) > props.maxAnswerChars()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Answers are 1 to " + props.maxAnswerChars() + " characters.");
        }
        Optional<String> violation = ContentRuleUtils.checkAnswer(text);
        if (violation.isPresent()) {
            runtime.recordModeration(
                    r.getSessionId(), ModerationStage.ANSWER, violation.get(), ModerationAction.BLOCKED);
            throw new ApiException(
                    ErrorCode.MODERATION_BLOCKED, "The host won't show that answer. Try something else.");
        }
        boolean first = duel.answerOf(p.getId()) == null;
        if (duel.getPlayerA().equals(p.getId())) {
            duel.setAnswerA(text);
        } else {
            duel.setAnswerB(text);
        }
        if (first) {
            p.addAnswersGiven(1);
            p.setFastestAnswerMs(Math.min(
                    p.getFastestAnswerMs(), runtime.now() - r.getRound().getAnsweringStartedMs()));
        }
        if (r.getPause() == null && allAnswered(r)) {
            endAnswering(r);
        }
    }

    boolean allAnswered(RoomState r) {
        Set<String> present = new HashSet<>(
                runtime.presentPlayers(r).stream().map(PlayerState::getId).toList());
        for (DuelState d : r.getRound().getDuels()) {
            if (present.contains(d.getPlayerA()) && d.getAnswerA() == null
                    || present.contains(d.getPlayerB()) && d.getAnswerB() == null) {
                return false;
            }
        }
        return true;
    }
    /** Answers are in: one AI call checks them all and writes a reaction per duel, then voting starts. */
    void endAnswering(RoomState r) {
        r.setDeadlineMs(null);
        List<HostAiService.DuelInput> inputs = new ArrayList<>();
        for (DuelState d : r.getRound().getDuels()) {
            if (d.getAnswerA() != null || d.getAnswerB() != null) {
                inputs.add(new HostAiService.DuelInput(
                        d.getId(),
                        d.getPrompt(),
                        r.getPlayers().get(d.getPlayerA()).getName(),
                        d.getAnswerA(),
                        r.getPlayers().get(d.getPlayerB()).getName(),
                        d.getAnswerB()));
            }
        }
        if (inputs.isEmpty()) {
            advanceRound(r);
            return;
        }
        r.setPending(RoomState.Pending.DUEL_REVIEW);
        r.setReviewPending(true);
        r.setThinkingUntilMs(runtime.now() + props.hostThinking().toMillis());
        HostAiService.CallContext ctx = runtime.ctx(r);
        Tone tone = r.getSettings().tone();
        Language language = r.getSettings().language();
        int round = r.getRoundNumber();
        runtime.async(r, () -> ai.reviewDuels(ctx, tone, language, inputs).orElse(null), (room, outcome) -> {
            HostAiService.DuelReview review = outcome.value();
            if (room.getRound() == null || room.getRound().getNumber() != round || !room.isReviewPending()) {
                return;
            }
            if (review != null) {
                for (DuelState d : room.getRound().getDuels()) {
                    if (Boolean.TRUE.equals(review.blocked().get(d.getId() + ":A")) && d.getAnswerA() != null) {
                        d.setBlockedA(true);
                        runtime.recordModeration(
                                room.getSessionId(), ModerationStage.ANSWER, "model", ModerationAction.BLOCKED);
                    }
                    if (Boolean.TRUE.equals(review.blocked().get(d.getId() + ":B")) && d.getAnswerB() != null) {
                        d.setBlockedB(true);
                        runtime.recordModeration(
                                room.getSessionId(), ModerationStage.ANSWER, "model", ModerationAction.BLOCKED);
                    }
                    d.setReaction(review.reactions().get(d.getId()));
                }
            }
            if (room.getPending() == RoomState.Pending.DUEL_REVIEW) {
                runtime.resolvePending(room, false);
            }
        });
    }

    void startDuel(RoomState r, int index) {
        RoundState round = r.getRound();
        for (int i = index; i < round.getDuels().size(); i++) {
            DuelState d = round.getDuels().get(i);
            round.setDuelIndex(i);
            if (!d.usableA() && !d.usableB()) {
                continue;
            }
            if (d.usableA() && d.usableB()) {
                Set<String> eligible = new HashSet<>(runtime.presentPlayers(r).stream()
                        .map(PlayerState::getId)
                        .filter(id -> !d.involves(id))
                        .toList());
                d.setVote(new VoteStepState(List.of("A", "B"), eligible));
                r.setPhase(Phase.VOTING);
                r.setDeadlineMs(runtime.now() + props.voting().toMillis());
                runtime.say(r, d.getPrompt(), Set.of());
                if (eligible.isEmpty()) {
                    reveal(r);
                }
            } else {
                d.setVote(new VoteStepState(List.of("A", "B"), Set.of()));
                reveal(r);
            }
            return;
        }
        advanceRound(r);
    }

    void vote(RoomState r, String playerId, String optionId) {
        VoteStepState step = r.getPhase() == Phase.VOTING && r.getRound() != null
                ? r.getRound().currentVote()
                : null;
        if (step == null) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
        if (!step.getEligible().contains(playerId)) {
            throw new ApiException(ErrorCode.NOT_ALLOWED, "You can't vote on this one.");
        }
        if (step.getVotes().containsKey(playerId)) {
            throw new ApiException(ErrorCode.INVALID_PHASE, "You already voted.");
        }
        if (!step.getOptions().contains(optionId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown option.");
        }
        step.putVote(playerId, optionId);
        if (r.getPause() == null && votingComplete(r, step)) {
            reveal(r);
        }
    }

    boolean votingComplete(RoomState r, VoteStepState step) {
        Set<String> present = new HashSet<>(
                runtime.presentPlayers(r).stream().map(PlayerState::getId).toList());
        return step.getEligible().stream().filter(present::contains).allMatch(step.getVotes()::containsKey);
    }

    void audienceVote(RoomState r, String audienceId, String optionId) {
        VoteStepState step = r.getPhase() == Phase.VOTING && r.getRound() != null
                ? r.getRound().currentVote()
                : null;
        if (step == null) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
        if (!step.getOptions().contains(optionId)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown option.");
        }
        if (step.addAudienceVoter(audienceId)) {
            step.addAudienceVote(optionId);
        }
    }
    // ------------------------------------------------------------------ reveal and scoring

    void reveal(RoomState r) {
        RoundState round = r.getRound();
        r.setPhase(Phase.REVEAL);
        r.setDeadlineMs(runtime.now() + props.reveal().toMillis());
        round.setPoints(new HashMap<>());
        switch (round.getKind()) {
            case ANSWER_DUEL -> revealDuel(r, Objects.requireNonNull(round.currentDuel(), "a duel is being voted on"));
            case WHO_OF_US -> revealWho(r, round);
            case TRUTH_OR_AI -> revealTruth(r, round);
        }
        if (isLastStep(r)) {
            runtime.requestFinale(r);
        }
    }

    boolean isLastStep(RoomState r) {
        if (r.getRoundNumber() < r.getRoundsTotal()) {
            return false;
        }
        RoundState round = r.getRound();
        if (round.getKind() != RoundKind.ANSWER_DUEL) {
            return true;
        }
        for (int i = round.getDuelIndex() + 1; i < round.getDuels().size(); i++) {
            DuelState d = round.getDuels().get(i);
            if (d.usableA() || d.usableB()) {
                return false;
            }
        }
        return true;
    }

    void award(RoomState r, String playerId, int points) {
        PlayerState p = r.getPlayers().get(playerId);
        if (p != null && points != 0) {
            p.addScore(points);
            r.getRound().addPoints(playerId, points);
        }
    }

    void revealDuel(RoomState r, DuelState d) {
        VoteStepState step = d.getVote();
        PlayerState a = r.getPlayers().get(d.getPlayerA());
        PlayerState b = r.getPlayers().get(d.getPlayerB());
        if (!d.usableA() || !d.usableB()) {
            PlayerState winner = d.usableA() ? a : b;
            PlayerState missing = d.usableA() ? b : a;
            int points = 100
                    * Math.max(1, (int) runtime.presentPlayers(r).stream()
                            .filter(p -> !d.involves(p.getId()))
                            .count());
            award(r, winner.getId(), points);
            winner.addDuelsWon(1);
            if (winner == a) {
                d.setPointsA(points);
            } else {
                d.setPointsB(points);
            }
            runtime.say(
                    r,
                    runtime.line(r, "duelNoAnswer", Map.of("winner", winner.getName(), "missing", missing.getName())),
                    Set.of(winner.getId(), missing.getId()));
            return;
        }
        int votesA = step.countFor("A");
        int votesB = step.countFor("B");
        int total = votesA + votesB;
        d.setPointsA(votesA * 100);
        d.setPointsB(votesB * 100);
        boolean landslide = total >= 2 && (votesA == 0 || votesB == 0);
        if (landslide) {
            if (votesA > 0) {
                d.addPointsA(250);
            } else {
                d.addPointsB(250);
            }
        }
        int audA = step.getAudience().getOrDefault("A", 0);
        int audB = step.getAudience().getOrDefault("B", 0);
        if (audA != audB) {
            if (audA > audB) {
                d.addPointsA(100);
            } else {
                d.addPointsB(100);
            }
        }
        award(r, a.getId(), d.getPointsA());
        award(r, b.getId(), d.getPointsB());
        a.addVotesReceived(votesA);
        b.addVotesReceived(votesB);
        trackAnswerOfNight(r, d.getAnswerA(), d.getPrompt(), a.getId(), votesA);
        trackAnswerOfNight(r, d.getAnswerB(), d.getPrompt(), b.getId(), votesB);
        String text;
        if (votesA == votesB) {
            text = runtime.line(r, "duelTie", Map.of());
        } else {
            PlayerState winner = votesA > votesB ? a : b;
            PlayerState loser = winner == a ? b : a;
            winner.addDuelsWon(1);
            text = runtime.line(
                    r,
                    landslide ? "duelLandslide" : "duelWin",
                    Map.of("winner", winner.getName(), "loser", loser.getName()));
        }
        if (d.getReaction() != null && !d.getReaction().isBlank()) {
            text = d.getReaction();
        }
        runtime.say(r, text, Set.of(a.getId(), b.getId()));
    }

    void trackAnswerOfNight(RoomState r, String answer, String prompt, String authorId, int votes) {
        if (answer != null && votes > r.getAnswerOfNightVotes() && votes > 0) {
            r.setAnswerOfNightVotes(votes);
            r.setAnswerOfNightText(answer);
            r.setAnswerOfNightPrompt(prompt);
            r.setAnswerOfNightAuthor(authorId);
        }
    }

    void revealWho(RoomState r, RoundState round) {
        VoteStepState step = round.getVote();
        Map<String, Integer> counts = new HashMap<>();
        step.getVotes().values().forEach(id -> counts.merge(id, 1, Integer::sum));
        int best = counts.values().stream().max(Integer::compare).orElse(0);
        if (best == 0) {
            runtime.say(r, runtime.line(r, "whoNobody", Map.of()), Set.of());
            return;
        }
        List<String> winners = counts.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        for (String w : winners) {
            award(r, w, 200);
            r.getPlayers().get(w).addWhoPicks(1);
        }
        step.getVotes().forEach((voter, pick) -> {
            if (winners.contains(pick)) {
                award(r, voter, 100);
            }
        });
        String names = String.join(
                " and ",
                winners.stream().map(id -> r.getPlayers().get(id).getName()).toList());
        runtime.say(r, runtime.line(r, "whoReveal", Map.of("name", names)), Set.copyOf(winners));
    }

    void revealTruth(RoomState r, RoundState round) {
        VoteStepState step = round.getVote();
        String correct = round.isStatementTrue() ? "truth" : "ai";
        PlayerState subject = r.getPlayers().get(round.getSubjectId());
        step.getVotes().forEach((voter, pick) -> {
            if (pick.equals(correct)) {
                award(r, voter, 200);
                r.getPlayers().get(voter).addCorrectGuesses(1);
            } else {
                award(r, subject.getId(), 100);
                subject.addPeopleFooled(1);
            }
        });
        String text = round.isStatementTrue() ? round.getTruthLine() : round.getFakeLine();
        if (text == null || text.isBlank()) {
            text = runtime.line(
                    r,
                    round.isStatementTrue() ? "truthRevealTrue" : "truthRevealFake",
                    Map.of("name", subject.getName()));
        }
        runtime.say(r, text, Set.of(subject.getId()));
    }

    void advance(RoomState r) {
        r.setDeadlineMs(null);
        RoundState round = r.getRound();
        if (round != null
                && round.getKind() == RoundKind.ANSWER_DUEL
                && round.getDuelIndex() + 1 < round.getDuels().size()) {
            startDuel(r, round.getDuelIndex() + 1);
            return;
        }
        advanceRound(r);
    }

    void advanceRound(RoomState r) {
        if (r.getRoundNumber() < r.getRoundsTotal()) {
            runtime.startRound(r, r.getRoundNumber() + 1);
        } else {
            runtime.enterFinale(r, EndReason.COMPLETED);
        }
    }
}
