package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AudienceDto(boolean voted, Integer total) {}
