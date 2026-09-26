package com.insidejoke.game;

import com.insidejoke.common.Language;

/**
 * Chosen on H3 and changeable by the owner in the lobby. {@code company} and {@code context} tell the host who came
 * (roadmap R39); {@code context} is the owner's own line ("classmates, ten years on"), already cleaned, or null.
 */
public record RoomSettings(
        Tone tone,
        GameLength length,
        RoomMode mode,
        boolean hideCode,
        Language language,
        Company company,
        String context) {

    public RoomSettings {
        language = language == null ? Language.EN : language;
        company = company == null ? Company.FRIENDS : company;
    }

    /** A group of friends with no context line. */
    public RoomSettings(Tone tone, GameLength length, RoomMode mode, boolean hideCode, Language language) {
        this(tone, length, mode, hideCode, language, Company.FRIENDS, null);
    }

    /** The tone of the prewritten content: family-safe for a group that must stay clean, whatever the tone. */
    public Tone contentTone() {
        return company.clean() ? Tone.FAMILY : tone;
    }
}
