package com.insidejoke.game;

public enum EndReason {
    /** All rounds were played. */
    COMPLETED,
    /** The owner ended the game early. */
    ENDED_BY_HOST,
    /** No activity for 30 minutes. */
    IDLE,
    /** The room reached its 4-hour lifetime. */
    MAX_AGE,
    /** Fewer than 3 players stayed connected past the waiting period. */
    NOT_ENOUGH_PLAYERS,
    /** The server stopped. */
    SHUTDOWN
}
