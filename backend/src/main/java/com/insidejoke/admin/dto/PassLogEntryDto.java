package com.insidejoke.admin.dto;

import com.insidejoke.billing.PassLogStatus;
import com.insidejoke.billing.PassSource;
import com.insidejoke.billing.Product;
import java.time.Instant;
import java.util.UUID;

public record PassLogEntryDto(
        UUID id,
        Product type,
        PassSource source,
        PassLogStatus status,
        UUID userId,
        String userEmail,
        boolean userDeleted,
        Instant createdAt,
        Instant startsAt,
        Instant endsAt,
        Integer monthlyGameLimit,
        int gamesPlayed,
        String txnId,
        Integer amountMinor,
        String currency,
        Instant refundedAt,
        String refundKind,
        String grantedByEmail,
        Instant revokedAt,
        String revokeReason,
        String revokedByEmail) {}
