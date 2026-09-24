package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.game.RoundKind;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KindVoteDto(Map<RoundKind, List<String>> voters, RoundKind myKind) {}
