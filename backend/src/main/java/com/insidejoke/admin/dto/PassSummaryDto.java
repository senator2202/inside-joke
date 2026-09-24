package com.insidejoke.admin.dto;

import java.util.List;
import java.util.Map;

public record PassSummaryDto(
        long sold,
        long granted,
        long refunded,
        long chargebacks,
        double refundRate,
        long activeNow,
        List<CurrencyRevenueDto> revenue,
        Map<String, PassTypeStatsDto> byType) {}
