package com.insidejoke.game;

/** Carries out one kind of {@link GameCommand} under the room lock; answers through {@link GameCommand#reply()}. */
@FunctionalInterface
public interface CommandHandler {

    void handle(GameCommand command);
}
