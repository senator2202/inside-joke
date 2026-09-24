package com.insidejoke.game;

import com.insidejoke.common.Language;
import com.insidejoke.moderation.ModerationStage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the game needs from the AI host. Every method blocks on the network, so the engine only calls them
 * from virtual threads, never under a room lock. An empty result means "use fallback content".
 */
public interface HostAiService {

    /** Identifies the game for cost accounting and carries the room's budget counters. */
    record CallContext(
            UUID sessionId,
            boolean freeGame,
            AtomicInteger llmCalls,
            AtomicInteger ttsCalls,
            AtomicInteger moderationCalls) {}

    record PlayerInfo(String id, String name, List<String> intakeAnswers) {}

    record Fact(String id, String aboutPlayerId, String text) {}

    record RoundParams(
            Tone tone,
            Language language,
            int roundNumber,
            int roundsTotal,
            List<PlayerInfo> players,
            List<Fact> dossier,
            List<String> recentPrompts) {}

    record DuelInput(String duelId, String prompt, String nameA, String answerA, String nameB, String answerB) {}

    /** Which answers must not be shown (keys "duelId:A" / "duelId:B") and one host reaction per duel. */
    record DuelReview(Map<String, Boolean> blocked, Map<String, String> reactions) {}

    record PlayerStats(
            String id,
            String name,
            int score,
            int rank,
            int duelsWon,
            int votesReceived,
            int peopleFooled,
            int correctGuesses,
            List<String> answers) {}

    /** Result of a content check. {@code available} is false when the model could not be asked. */
    record Verdicts(boolean available, List<Boolean> allowed, List<String> categories) {}

    Optional<RoundContent> generateRound(CallContext ctx, RoundParams request);

    Optional<DuelReview> reviewDuels(CallContext ctx, Tone tone, Language language, List<DuelInput> duels);

    Optional<Finale> finale(CallContext ctx, Tone tone, Language language, List<PlayerStats> players);

    Verdicts moderate(CallContext ctx, ModerationStage stage, List<String> texts);

    /** False when secrets can never be accepted here: no model to check them and rules alone are not allowed. */
    boolean secretsCheckable();

    /** Synthesises speech and returns an audio id for GET /api/voice-lines/{id}. */
    Optional<String> speak(CallContext ctx, String text);
}
