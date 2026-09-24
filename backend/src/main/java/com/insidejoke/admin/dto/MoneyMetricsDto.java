package com.insidejoke.admin.dto;

import java.util.List;
import java.util.Map;

public record MoneyMetricsDto(
        List<CurrencyTotalDto> revenue, int purchases, Map<String, Integer> byProduct, int refunds) {}
