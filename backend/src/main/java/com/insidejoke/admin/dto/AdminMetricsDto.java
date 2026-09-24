package com.insidejoke.admin.dto;

import com.insidejoke.game.dto.LiveStatsDto;
import java.time.LocalDate;
import java.util.List;

public record AdminMetricsDto(
        LocalDate from,
        LocalDate to,
        GameMetricsDto games,
        MoneyMetricsDto money,
        AiMetricsDto ai,
        FunnelMetricsDto funnel,
        LiveStatsDto live,
        List<DayMetricsDto> days) {}
