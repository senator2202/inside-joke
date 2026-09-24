package com.insidejoke.game.dto;

/** What is running right now, for the admin panel and for knowing when a drained server is empty. */
public record LiveStatsDto(int rooms, int inGame, int players, int viewers) {}
