package com.insidejoke.game;

import com.insidejoke.common.Language;
import java.util.UUID;

/**
 * Whether a host may start a game, and recording it when they may. The game core owns this port; billing implements it
 * (dependency inversion: billing depends on the game, never the other way round).
 */
public interface GameAccessPort {

    record StartParams(
            UUID hostUserId,
            UUID previousSessionId,
            String roomCode,
            RoomMode mode,
            Tone tone,
            GameLength length,
            String promptVersion,
            int playerCount,
            Language language) {}

    /**
     * @param passType the pass the game was paid with (PARTY_PASS, HOST_PASS), or null for a free game
     */
    record Started(UUID sessionId, boolean free, String passType) {}

    /** Records the game and the pass it uses, or throws an ApiException with the paywall reason. */
    Started start(StartParams params);
}
