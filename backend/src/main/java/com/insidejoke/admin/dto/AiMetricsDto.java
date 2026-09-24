package com.insidejoke.admin.dto;

import java.util.Map;

public record AiMetricsDto(
        long costMicros,
        long freeGameCostMicros,
        long costPerGameMicros,
        int calls,
        int failures,
        Map<String, Long> costByPurpose) {}
