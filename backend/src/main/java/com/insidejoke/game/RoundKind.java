package com.insidejoke.game;

public enum RoundKind {
    /** Two players answer the same prompt, everyone else votes for the funnier one. */
    ANSWER_DUEL,
    /** Everyone votes for the player who best fits a question. */
    WHO_OF_US,
    /** A statement about a player: true secret or AI invention? */
    TRUTH_OR_AI
}
