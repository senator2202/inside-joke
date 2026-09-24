package com.insidejoke.game;

import com.insidejoke.analytics.AnalyticsService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * Choosing the next round kind and getting its content from the AI or the prewritten fallback. Runs under the
 * room lock taken by {@link GameEngineService}.
 */
final class RoundPhaseHandler {

    private final GameRuntimeService runtime;
    private final GameProperties props;
    private final HostAiService ai;
    private final FallbackContentService fallback;
    private final AnalyticsService analytics;
    private final RandomGenerator random;

    RoundPhaseHandler(
            GameRuntimeService runtime,
            GameProperties props,
            HostAiService ai,
            FallbackContentService fallback,
            AnalyticsService analytics,
            RandomGenerator random) {
        this.runtime = runtime;
        this.props = props;
        this.ai = ai;
        this.fallback = fallback;
        this.analytics = analytics;
        this.random = random;
    }

    void startRound(RoomState r, int n) {
        r.setRoundNumber(n);
        r.setRound(null);
        r.clearKindVote();
        if (n == 1) {
            decideKind(r, RoundKind.ANSWER_DUEL);
            return;
        }
        r.setPhase(Phase.ROUND_VOTE);
        r.setDeadlineMs(runtime.now() + props.roundVote().toMillis());
    }

    void voteKind(RoomState r, String playerId, String rawKind) {
        GameRuleUtils.requirePhase(r, Phase.ROUND_VOTE);
        RoundKind kind = GameRuleUtils.enumValue(RoundKind.class, rawKind);
        r.putKindVote(playerId, kind);
        analytics.track("round_kind_voted", r.getCode() + ":" + playerId, Map.of("kind", kind.name()));
    }

    void endRoundVote(RoomState r) {
        r.setDeadlineMs(null);
        Map<RoundKind, Integer> tally = new LinkedHashMap<>();
        r.getKindVotes().values().forEach(k -> tally.merge(k, 1, Integer::sum));
        int best = tally.values().stream().max(Integer::compare).orElse(0);
        List<RoundKind> candidates = new ArrayList<>();
        for (RoundKind k : RoundKind.values()) {
            if (best == 0 || tally.getOrDefault(k, 0) == best) {
                candidates.add(k);
            }
        }
        int fewest = candidates.stream()
                .mapToInt(k -> r.getKindCounts().getOrDefault(k, 0))
                .min()
                .orElse(0);
        List<RoundKind> leastPlayed = candidates.stream()
                .filter(k -> r.getKindCounts().getOrDefault(k, 0) == fewest)
                .toList();
        RoundKind chosen = leastPlayed.get(random.nextInt(leastPlayed.size()));
        if (best == 0) {
            runtime.say(r, fallback.label(r.getSettings().language(), "hostPicks", Map.of()), Set.of());
        }
        decideKind(r, chosen);
    }

    void decideKind(RoomState r, RoundKind kind) {
        r.mergeKindCount(kind, 1, Integer::sum);
        requestContent(r, r.getRoundNumber());
        if (r.getContent().containsKey(GameRuleUtils.contentKey(r, r.getRoundNumber()))) {
            materialize(r, kind);
            return;
        }
        r.setPending(RoomState.Pending.ROUND_CONTENT);
        r.setPendingKind(kind);
        r.setThinkingUntilMs(runtime.now() + props.hostThinking().toMillis());
    }

    void resolvePending(RoomState r, boolean timedOut) {
        RoomState.Pending pending = r.getPending();
        r.setPending(null);
        r.setThinkingUntilMs(null);
        if (pending == null) {
            return;
        }
        switch (pending) {
            case ROUND_CONTENT -> {
                if (timedOut) {
                    r.putIfAbsentContent(
                            GameRuleUtils.contentKey(r, r.getRoundNumber()),
                            fallback.round(
                                    r.getSettings().language(),
                                    r.getSettings().tone(),
                                    runtime.presentPlayers(r),
                                    r.getRecentPrompts(),
                                    random));
                }
                materialize(r, r.getPendingKind());
            }
            case DUEL_REVIEW -> {
                r.setReviewPending(false);
                runtime.startDuel(r, 0);
            }
            case FINALE -> {
                if (r.getFinale() == null) {
                    r.setFinale(runtime.fallbackFinale(r));
                }
                runtime.announceFinale(r);
            }
        }
    }
    /** Asks the AI for one round's content ahead of time (blueprint 4.4); fallback fills in on failure. */
    void requestContent(RoomState r, int n) {
        int key = GameRuleUtils.contentKey(r, n);
        if (n > r.getRoundsTotal() || !r.addContentRequested(key)) {
            return;
        }
        HostAiService.RoundParams request = roundRequest(r, n);
        HostAiService.CallContext ctx = runtime.ctx(r);
        runtime.async(r, () -> ai.generateRound(ctx, request).orElse(null), (room, outcome) -> {
            RoundContent generated = outcome.value();
            if (generated == null) {
                generated = fallback.round(
                        room.getSettings().language(),
                        room.getSettings().tone(),
                        runtime.presentPlayers(room),
                        room.getRecentPrompts(),
                        random);
            }
            room.putIfAbsentContent(key, generated);
            if (room.getPending() == RoomState.Pending.ROUND_CONTENT
                    && GameRuleUtils.contentKey(room, room.getRoundNumber()) == key) {
                resolvePending(room, false);
            }
        });
    }

    HostAiService.RoundParams roundRequest(RoomState r, int n) {
        List<HostAiService.PlayerInfo> players = new ArrayList<>();
        for (PlayerState p : r.activePlayers()) {
            List<String> answers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                if (p.getIntake()[i] != null) {
                    answers.add(r.getIntakeQuestions().get(i) + " " + p.getIntake()[i]);
                }
            }
            players.add(new HostAiService.PlayerInfo(p.getId(), p.getName(), answers));
        }
        List<HostAiService.Fact> facts = r.getDossier().stream()
                .map(f -> new HostAiService.Fact(f.id(), f.aboutPlayerId(), f.text()))
                .toList();
        return new HostAiService.RoundParams(
                r.getSettings().tone(),
                r.getSettings().language(),
                n,
                r.getRoundsTotal(),
                players,
                facts,
                List.copyOf(r.getRecentPrompts()));
    }

    void materialize(RoomState r, RoundKind kind) {
        RoundContent content = r.getContent().get(GameRuleUtils.contentKey(r, r.getRoundNumber()));
        List<PlayerState> present = new ArrayList<>(runtime.presentPlayers(r));
        Collections.shuffle(present, random);
        RoundState round = new RoundState(r.getRoundNumber(), kind);
        r.setRound(round);
        switch (kind) {
            case ANSWER_DUEL -> {
                List<RoundContent.DuelPrompt> prompts = new ArrayList<>(content.duelPrompts());
                prompts.removeIf(p -> r.getRecentPrompts().contains(p.text()));
                if (prompts.size() < present.size()) {
                    RoundContent extra = fallback.round(
                            r.getSettings().language(), r.getSettings().tone(), present, r.getRecentPrompts(), random);
                    prompts.addAll(extra.duelPrompts());
                }
                int n = present.size();
                for (int i = 0; i < n; i++) {
                    PlayerState a = present.get(i);
                    PlayerState b = present.get((i + 1) % n);
                    RoundContent.DuelPrompt prompt = prompts.stream()
                            .filter(p -> p.aboutPlayerId() == null
                                    || !p.aboutPlayerId().equals(a.getId())
                                            && !p.aboutPlayerId().equals(b.getId()))
                            .findFirst()
                            .orElse(prompts.getFirst());
                    prompts.remove(prompt);
                    r.addRecentPrompt(prompt.text());
                    round.addDuel(new DuelState(r.nextId("d"), prompt.text(), a.getId(), b.getId()));
                }
                round.setAnsweringStartedMs(runtime.now());
                r.setPhase(Phase.ANSWERING);
                r.setDeadlineMs(runtime.now() + props.answering().toMillis());
                r.setHostLine(null);
            }
            case WHO_OF_US -> {
                String question = content.whoOfUsQuestion();
                r.addRecentPrompt(question);
                round.setQuestion(question);
                Set<String> eligible =
                        new HashSet<>(present.stream().map(p -> p.getId()).toList());
                List<String> options = r.activePlayers().stream()
                        .filter(p -> eligible.contains(p.getId()))
                        .map(p -> p.getId())
                        .toList();
                round.setVote(new VoteStepState(options, eligible));
                r.setPhase(Phase.VOTING);
                r.setDeadlineMs(runtime.now() + props.voting().toMillis());
                runtime.say(r, question, Set.of());
            }
            case TRUTH_OR_AI -> {
                RoundContent.Truth proposed = content.truth();
                boolean subjectOk = proposed != null
                        && present.stream().anyMatch(p -> p.getId().equals(proposed.aboutPlayerId()));
                RoundContent.Truth truth = subjectOk
                        ? proposed
                        : fallback.round(
                                        r.getSettings().language(),
                                        r.getSettings().tone(),
                                        present,
                                        r.getRecentPrompts(),
                                        random)
                                .truth();
                boolean useTruth = truth.truthStatement() != null && random.nextBoolean();
                round.setSubjectId(truth.aboutPlayerId());
                round.setStatementTrue(useTruth);
                round.setStatement(useTruth ? truth.truthStatement() : truth.fakeStatement());
                round.setTruthLine(truth.truthLine());
                round.setFakeLine(truth.fakeLine());
                String subject = round.getSubjectId();
                Set<String> eligible = new HashSet<>(present.stream()
                        .map(p -> p.getId())
                        .filter(id -> !id.equals(subject))
                        .toList());
                round.setVote(new VoteStepState(List.of("truth", "ai"), eligible));
                r.setPhase(Phase.VOTING);
                r.setDeadlineMs(runtime.now() + props.voting().toMillis());
                runtime.say(r, round.getStatement(), Set.of(subject));
            }
        }
        requestContent(r, r.getRoundNumber() + 1);
    }
}
