package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlayerDto(
        String id,
        String name,
        String emoji,
        boolean connected,
        int score,
        boolean captain,
        String status,
        Integer rank) {}
