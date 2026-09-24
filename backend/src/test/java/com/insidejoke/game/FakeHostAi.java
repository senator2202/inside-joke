package com.insidejoke.game;

import com.insidejoke.common.Language;
import com.insidejoke.moderation.ModerationStage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The AI host of unit tests: answers at once, builds its answers from the request, and can be switched off. Any text
 * containing "BANNED" fails its checks, as with the real model's moderation.
 */
final class FakeHostAi implements HostAiService {

    /** Every call fails, as in an outage: no content, no checks, no voice. */
    boolean down;

    boolean secretsCheckable = true;

    final List<RoundParams> roundRequests = new ArrayList<>();
    final List<List<String>> moderated = new ArrayList<>();
    int finaleRequests;
    private int voices;

    @Override
    public Optional<RoundContent> generateRound(CallContext ctx, RoundParams request) {
        roundRequests.add(request);
        if (down) {
            return Optional.empty();
        }
        int n = request.roundNumber();
        List<RoundContent.DuelPrompt> prompts = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            prompts.add(new RoundContent.DuelPrompt("AI prompt " + i + " for round " + n, null));
        }
        RoundContent.Truth truth;
        if (request.dossier().isEmpty()) {
            PlayerInfo subject = request.players().getFirst();
            truth = new RoundContent.Truth(subject.id(), null, "AI invention about " + subject.name(), null, null);
        } else {
            Fact fact = request.dossier().getFirst();
            truth = new RoundContent.Truth(
                    fact.aboutPlayerId(),
                    "Restated: " + fact.text(),
                    "AI invention for round " + n,
                    "AI truth line",
                    "AI fake line");
        }
        return Optional.of(new RoundContent(prompts, "AI question for round " + n, truth, "AI"));
    }

    @Override
    public Optional<DuelReview> reviewDuels(CallContext ctx, Tone tone, Language language, List<DuelInput> duels) {
        if (down) {
            return Optional.empty();
        }
        Map<String, Boolean> blocked = new LinkedHashMap<>();
        Map<String, String> reactions = new LinkedHashMap<>();
        for (DuelInput d : duels) {
            blocked.put(d.duelId() + ":A", banned(d.answerA()));
            blocked.put(d.duelId() + ":B", banned(d.answerB()));
            reactions.put(d.duelId(), "AI reaction to " + d.duelId());
        }
        return Optional.of(new DuelReview(blocked, reactions));
    }

    @Override
    public Optional<Finale> finale(CallContext ctx, Tone tone, Language language, List<PlayerStats> players) {
        finaleRequests++;
        if (down) {
            return Optional.empty();
        }
        Map<String, String> titles = new LinkedHashMap<>();
        players.forEach(p -> titles.put(p.id(), "AI title for " + p.name()));
        return Optional.of(new Finale(titles, "AI closing speech", "AI"));
    }

    @Override
    public Verdicts moderate(CallContext ctx, ModerationStage stage, List<String> texts) {
        moderated.add(texts);
        if (down) {
            return new Verdicts(false, List.of(), List.of());
        }
        return new Verdicts(
                true,
                texts.stream().map(t -> !banned(t)).toList(),
                texts.stream().map(t -> banned(t) ? "harmful" : "ok").toList());
    }

    @Override
    public boolean secretsCheckable() {
        return secretsCheckable;
    }

    @Override
    public Optional<String> speak(CallContext ctx, String text) {
        return down ? Optional.empty() : Optional.of("voice-" + ++voices);
    }

    private static boolean banned(String text) {
        return text != null && text.contains("BANNED");
    }
}
