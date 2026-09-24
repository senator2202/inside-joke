package com.insidejoke.game;

/**
 * Ends one phase and moves the room on: what happens when the host presses "next" or the phase timer runs out. The
 * coordinator keeps one per phase that has an end (State pattern, table-driven); phases without one have no entry.
 */
@FunctionalInterface
public interface PhaseHandler {

    void finish(RoomState room);
}
