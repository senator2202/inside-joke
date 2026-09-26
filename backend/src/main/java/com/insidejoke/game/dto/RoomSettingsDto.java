package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.game.Company;
import com.insidejoke.game.GameLength;
import com.insidejoke.game.RoomMode;
import com.insidejoke.game.Tone;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoomSettingsDto(
        Tone tone,
        GameLength length,
        RoomMode mode,
        boolean hideCode,
        String language,
        // Who came and the owner's line about the group (roadmap R39); context is absent when not given.
        Company company,
        String context) {}
