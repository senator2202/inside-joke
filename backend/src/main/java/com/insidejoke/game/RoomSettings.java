package com.insidejoke.game;

import com.insidejoke.common.Language;

/** Chosen on H3 and changeable by the owner in the lobby. */
public record RoomSettings(Tone tone, GameLength length, RoomMode mode, boolean hideCode, Language language) {

    public RoomSettings {
        language = language == null ? Language.EN : language;
    }
}
