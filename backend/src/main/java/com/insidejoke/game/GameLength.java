package com.insidejoke.game;

public enum GameLength {
    SHORT(5),
    LONG(10);

    private final int rounds;

    GameLength(int rounds) {
        this.rounds = rounds;
    }

    public int rounds() {
        return rounds;
    }
}
