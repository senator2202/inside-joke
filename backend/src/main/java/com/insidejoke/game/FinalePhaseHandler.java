package com.insidejoke.game;

import com.insidejoke.common.Language;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.random.RandomGenerator;

/** The finale: standings, titles and the closing speech of the host. Runs under the room lock taken by {@link GameEngineService}. */
final class FinalePhaseHandler {

    private final GameRuntimeService runtime;
    private final HostAiService ai;
    private final FallbackContentService fallback;
    private final RandomGenerator random;

    FinalePhaseHandler(
            GameRuntimeService runtime, HostAiService ai, FallbackContentService fallback, RandomGenerator random) {
        this.runtime = runtime;
        this.ai = ai;
        this.fallback = fallback;
        this.random = random;
    }

    // ------------------------------------------------------------------ finale

    void requestFinale(RoomState r) {
        if (r.isFinaleRequested()) {
            return;
        }
        r.setFinaleRequested(true);
        List<HostAiService.PlayerStats> stats = standings(r).stream()
                .map(p -> new HostAiService.PlayerStats(
                        p.getId(),
                        p.getName(),
                        p.getScore(),
                        rank(r, p),
                        p.getDuelsWon(),
                        p.getVotesReceived(),
                        p.getPeopleFooled(),
                        p.getCorrectGuesses(),
                        answersOf(r, p.getId())))
                .toList();
        HostAiService.CallContext ctx = runtime.ctx(r);
        Tone tone = r.getSettings().tone();
        Language language = r.getSettings().language();
        int game = r.getGameNumber();
        runtime.async(r, () -> ai.finale(ctx, tone, language, stats).orElse(null), (room, outcome) -> {
            if (room.getGameNumber() != game || room.getFinale() != null) {
                // Another game by now, or the fallback finale is already on screen: the titles people saw stay.
                return;
            }
            Finale generated = outcome.value();
            if (generated == null) {
                // The model failed: nothing to wait for, the fallback is ready when the finale comes.
                room.setFinale(fallbackFinale(room));
            } else {
                Map<String, String> titles = new HashMap<>(fallbackFinale(room).titles());
                generated.titles().forEach((id, title) -> {
                    if (room.getPlayers().containsKey(id) && title != null && !title.isBlank()) {
                        titles.put(id, title);
                    }
                });
                room.setFinale(new Finale(titles, generated.speech(), generated.source()));
            }
            if (room.getPending() == RoomState.Pending.FINALE) {
                runtime.resolvePending(room, false);
            }
        });
    }

    List<String> answersOf(RoomState r, String playerId) {
        List<String> out = new ArrayList<>();
        if (r.getRound() != null) {
            for (DuelState d : r.getRound().getDuels()) {
                if (d.involves(playerId) && d.answerOf(playerId) != null) {
                    out.add(d.answerOf(playerId));
                }
            }
        }
        return out;
    }

    List<PlayerState> standings(RoomState r) {
        return r.activePlayers().stream()
                .sorted(Comparator.comparingInt((PlayerState p) -> -p.getScore())
                        .thenComparing(PlayerState::getJoinedAt))
                .toList();
    }

    int rank(RoomState r, PlayerState p) {
        return 1
                + (int) r.activePlayers().stream()
                        .filter(o -> o.getScore() > p.getScore())
                        .count();
    }
    /** Titles from game statistics when the AI finale isn't available (user flow S8). */
    Finale fallbackFinale(RoomState r) {
        List<PlayerState> players = standings(r);
        Map<String, String> titles = new LinkedHashMap<>();
        if (players.isEmpty()) {
            return new Finale(titles, runtime.line(r, "finaleSpeech", Map.of("winner", "nobody")), "FALLBACK");
        }
        titles.put(players.getFirst().getId(), fallback.title(r.getSettings().language(), "winner"));
        assignTitle(
                r.getSettings().language(),
                titles,
                players,
                p -> p.getFastestAnswerMs() == Long.MAX_VALUE ? 0 : Long.MAX_VALUE - p.getFastestAnswerMs(),
                "fastest");
        assignTitle(r.getSettings().language(), titles, players, PlayerState::getPeopleFooled, "fooler");
        assignTitle(r.getSettings().language(), titles, players, PlayerState::getCorrectGuesses, "detective");
        assignTitle(r.getSettings().language(), titles, players, PlayerState::getWhoPicks, "whoMagnet");
        assignTitle(r.getSettings().language(), titles, players, PlayerState::getVotesReceived, "votes");
        List<String> generic =
                new ArrayList<>(fallback.genericTitles(r.getSettings().language()));
        Collections.shuffle(generic, random);
        int g = 0;
        for (PlayerState p : players) {
            if (!titles.containsKey(p.getId())) {
                titles.put(p.getId(), generic.get(g++ % generic.size()));
            }
        }
        String winnerNames = String.join(
                " and ",
                players.stream()
                        .filter(p -> p.getScore() == players.getFirst().getScore())
                        .map(PlayerState::getName)
                        .toList());
        return new Finale(titles, runtime.line(r, "finaleSpeech", Map.of("winner", winnerNames)), "FALLBACK");
    }

    void assignTitle(
            Language language,
            Map<String, String> titles,
            List<PlayerState> players,
            ToLongFunction<PlayerState> metric,
            String kind) {
        players.stream()
                .filter(p -> !titles.containsKey(p.getId()) && metric.applyAsLong(p) > 0)
                .max(Comparator.comparingLong(metric))
                .ifPresent(p -> titles.put(p.getId(), fallback.title(language, kind)));
    }
}
