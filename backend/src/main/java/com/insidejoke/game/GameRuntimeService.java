package com.insidejoke.game;

import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * What the phase handlers may ask of the game coordinator (Mediator): time, AI call context, background work, the
 * host's lines, and moving the room to another phase. Handlers never reach into the coordinator or into each other; a
 * transition to another phase goes through here and the coordinator routes it to the handler that owns that phase.
 */
public interface GameRuntimeService {

    long now();

    HostAiService.CallContext ctx(RoomState room);

    /** Runs {@code work} off the room lock, then {@code then} under it (skipped if the room is gone). */
    <T> void async(RoomState room, Supplier<T> work, BiConsumer<RoomState, AsyncOutcome<T>> then);

    void say(RoomState room, String text, Set<String> about);

    String line(RoomState room, String kind, Map<String, String> values);

    List<PlayerState> presentPlayers(RoomState room);

    void checkPlayerCount(RoomState room);

    void recordModeration(UUID sessionId, ModerationStage stage, String category, ModerationAction action);

    void announceFinale(RoomState room);

    void enterFinale(RoomState room, EndReason reason);

    // ---------------------------------------------------------------- transitions to other phases

    void startRound(RoomState room, int number);

    void startDuel(RoomState room, int index);

    void resolvePending(RoomState room, boolean timedOut);

    void requestFinale(RoomState room);

    Finale fallbackFinale(RoomState room);
}
