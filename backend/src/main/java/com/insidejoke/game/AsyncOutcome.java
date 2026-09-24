package com.insidejoke.game;

/** What came of background work: its value, or the error it failed with. */
public record AsyncOutcome<T>(T value, RuntimeException error) {}
