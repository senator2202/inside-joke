package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnswerProgressDto(String playerId, int answered, int total) {}
