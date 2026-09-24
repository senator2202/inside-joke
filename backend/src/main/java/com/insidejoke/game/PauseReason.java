package com.insidejoke.game;

public enum PauseReason {
    /** The owner pressed pause. */
    OWNER,
    /** The shared screen was gone for more than 10 seconds. */
    SCREEN_LOST,
    /** Fewer than 3 players are connected. */
    WAITING_FOR_PLAYERS
}
