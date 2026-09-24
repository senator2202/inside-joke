package com.insidejoke.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record AiCallDto(
        long id,
        Instant createdAt,
        String purpose,
        String provider,
        String model,
        String promptVersion,
        String outcome,
        Integer inputTokens,
        Integer outputTokens,
        Integer ttsChars,
        long costMicros,
        int latencyMs,
        boolean freeGame,
        UUID gameSessionId,
        String roomCode,
        String error) {}
