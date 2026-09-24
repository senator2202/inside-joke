package com.insidejoke.admin.dto;

import java.time.Instant;

public record AiCallSummaryDto(Instant at, String purpose, String model, String outcome, int latencyMs, String error) {}
