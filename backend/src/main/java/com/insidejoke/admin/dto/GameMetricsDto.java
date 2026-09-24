package com.insidejoke.admin.dto;

import java.util.Map;

public record GameMetricsDto(
        int total, int free, int paid, int completed, double avgPlayers, Map<String, Integer> endReasons) {}
