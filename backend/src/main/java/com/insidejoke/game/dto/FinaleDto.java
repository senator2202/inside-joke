package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinaleDto(
        List<PlayerDto> standings,
        Map<String, String> titles,
        List<String> winnerIds,
        String answerOfNight,
        String answerOfNightPrompt,
        String answerOfNightAuthorId,
        String speech,
        boolean ready) {}
