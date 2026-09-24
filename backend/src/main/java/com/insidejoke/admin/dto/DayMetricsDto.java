package com.insidejoke.admin.dto;

import java.time.LocalDate;

public record DayMetricsDto(LocalDate date, int games, int paidGames, long aiCostMicros) {}
