package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LobbyDto(String joinUrl, String audienceUrl, int audienceCount, int minPlayers, int maxPlayers) {}
