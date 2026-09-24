package com.insidejoke.admin.dto;

import com.insidejoke.game.dto.LiveStatsDto;

public record DrainStateDto(boolean drainMode, LiveStatsDto live) {}
