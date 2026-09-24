package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OptionDto(
        String id,
        String text,
        String playerId,
        Integer votes,
        Integer audienceVotes,
        String authorId,
        Integer points,
        Boolean winner,
        Boolean correct) {}
