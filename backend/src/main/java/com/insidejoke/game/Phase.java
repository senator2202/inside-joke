package com.insidejoke.game;

public enum Phase {
    LOBBY,
    INTAKE,
    ROUND_VOTE,
    ANSWERING,
    VOTING,
    REVEAL,
    FINALE,
    CLOSED;

    /** Phases in which a game is running and the 3-player minimum applies. */
    public boolean inGame() {
        return this == INTAKE || this == ROUND_VOTE || this == ANSWERING || this == VOTING || this == REVEAL;
    }
}
