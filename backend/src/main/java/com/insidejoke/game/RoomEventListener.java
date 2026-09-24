package com.insidejoke.game;

/** Outbound notifications from the engine; implemented by the WebSocket layer. Must not block. */
public interface RoomEventListener {

    /** State changed: send every connection a fresh snapshot (coalesced). */
    void changed(RoomState room);

    /** A player was removed by the owner. */
    void kicked(RoomState room, String playerId);

    /** The room is closed; tell everyone and drop connections. */
    void closed(RoomState room);
}
