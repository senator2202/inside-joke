package com.insidejoke.admin.dto;

import java.time.Instant;

public record RateLimitsDto(
        Instant capturedAt,
        int httpStatus,
        RateLimitWindowDto requests,
        RateLimitWindowDto tokens,
        RateLimitWindowDto inputTokens,
        RateLimitWindowDto outputTokens,
        String retryAfter) {}
