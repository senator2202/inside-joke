package com.insidejoke.admin.dto;

public record AiStatusDto(
        boolean keyConfigured,
        String model,
        AiCallSummaryDto lastCall,
        AiCallSummaryDto lastFailure,
        RateLimitsDto rateLimits) {}
