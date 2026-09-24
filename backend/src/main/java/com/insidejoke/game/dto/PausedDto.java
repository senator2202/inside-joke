package com.insidejoke.game.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.insidejoke.game.PauseReason;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PausedDto(PauseReason reason, boolean welcomeBack, Long remainingMs) {}
