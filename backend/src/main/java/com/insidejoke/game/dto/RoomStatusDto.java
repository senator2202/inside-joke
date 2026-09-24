package com.insidejoke.game.dto;

import com.insidejoke.game.Phase;
import com.insidejoke.game.RoomMode;

/** What the join screen (P1) needs to know before asking for a name. */
public record RoomStatusDto(
        String code,
        Phase phase,
        RoomMode mode,
        int players,
        int maxPlayers,
        boolean locked,
        boolean full,
        boolean joinable,
        boolean audienceOpen,
        boolean hideCode,
        String audienceKey,
        String language) {}
