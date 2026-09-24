package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakeDto(List<String> questions, int done, int total) {}
