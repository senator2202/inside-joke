package com.insidejoke.admin.dto;

import java.time.Instant;
import java.util.UUID;

public record NotAppliedEventDto(
        String eventId,
        String eventType,
        Instant receivedAt,
        String action,
        String status,
        String txnId,
        String reason,
        String detail,
        UUID purchaseId,
        String product,
        Integer amountMinor,
        String currency,
        String purchaseStatus,
        UUID userId,
        String userEmail,
        UUID passId,
        Instant passRevokedAt,
        Instant passEndsAt,
        boolean passHeld) {}
