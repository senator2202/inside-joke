package com.insidejoke.game;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;

/** Checks and conversions shared by the engine and the phase handlers: permissions, player lookup, enum values. */
public final class GameRuleUtils {

    private GameRuleUtils() {}

    public static void require(Role role, Action action) {
        if (!action.allowedFor(role)) {
            throw new ApiException(ErrorCode.NOT_ALLOWED);
        }
    }

    public static PlayerState activePlayer(RoomState r, String playerId) {
        PlayerState p = r.getPlayers().get(playerId);
        if (p == null || p.isRemoved()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No such player.");
        }
        return p;
    }

    public static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown value '" + value + "'.");
        }
    }

    public static int contentKey(RoomState r, int round) {
        return r.getGameNumber() * 100 + round;
    }

    /** Throws INVALID_PHASE unless the room is in {@code phase}. */
    public static void requirePhase(RoomState room, Phase phase) {
        if (room.getPhase() != phase) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
    }
}
