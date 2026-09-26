package com.insidejoke.game;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.moderation.ContentRuleUtils;
import java.util.Map;

/** Checks and conversions shared by the engine and the phase handlers: permissions, player lookup, enum values. */
public final class GameRuleUtils {

    /** Longest line the owner may write about the group (roadmap R39). */
    public static final int MAX_CONTEXT_CHARS = 120;

    private GameRuleUtils() {}

    /**
     * The owner's line about the group ("classmates, ten years on"): cleaned, at most {@link #MAX_CONTEXT_CHARS}
     * characters, with no contacts, links or topics the host never touches; null when blank.
     */
    public static String groupContext(String raw) {
        String text = ContentRuleUtils.clean(raw);
        if (text.isEmpty()) {
            return null;
        }
        if (text.codePointCount(0, text.length()) > MAX_CONTEXT_CHARS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Describe the group in at most " + MAX_CONTEXT_CHARS + " characters.",
                    Map.of("fields", Map.of("context", "at most " + MAX_CONTEXT_CHARS + " characters")));
        }
        if (ContentRuleUtils.checkPersonal(text).isPresent()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Leave out contacts, links and sensitive topics.",
                    Map.of("fields", Map.of("context", "no contacts, links or sensitive topics")));
        }
        return text;
    }

    /** Coworkers and families don't get the Spicy tone (roadmap R39). */
    public static void requireFits(Company company, Tone tone) {
        if (!company.allows(tone)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Spicy is for friends and couples.",
                    Map.of("fields", Map.of("tone", "not Spicy for " + company.name())));
        }
    }

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
