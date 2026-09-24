package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.game.RoundKind;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RoundDto(
        int n,
        int of,
        RoundKind kind,
        Integer duelIndex,
        Integer duelCount,
        String prompt,
        String question,
        String statement,
        String subjectId,
        List<OptionDto> options,
        List<String> voters,
        Integer eligible,
        Boolean landslide,
        Boolean lastOfRound,
        Map<String, Integer> points,
        Integer audienceTotal,
        List<AnswerProgressDto> progress) {}
