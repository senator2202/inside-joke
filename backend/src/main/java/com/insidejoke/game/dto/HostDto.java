package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record HostDto(String lineId, String text, String audioId, boolean skipped, Boolean aboutYou, Boolean canSkip) {}
