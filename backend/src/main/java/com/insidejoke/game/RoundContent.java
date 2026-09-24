package com.insidejoke.game;

import java.util.List;

/**
 * Everything one round could need, prepared ahead for all three kinds (blueprint 4.4), so the kind vote
 * never waits for the AI. {@code source} is AI or FALLBACK.
 */
public record RoundContent(List<DuelPrompt> duelPrompts, String whoOfUsQuestion, Truth truth, String source) {

    public record DuelPrompt(String text, String aboutPlayerId) {}

    /** A statement about a player: a restated secret and an invented one; the engine picks which to show. */
    public record Truth(
            String aboutPlayerId, String truthStatement, String fakeStatement, String truthLine, String fakeLine) {}
}
