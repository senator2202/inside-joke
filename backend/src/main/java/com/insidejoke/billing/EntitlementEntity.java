package com.insidejoke.billing;

import java.time.Instant;
import java.util.UUID;

public record EntitlementEntity(
        UUID id,
        UUID userId,
        UUID purchaseId,
        Product type,
        Instant startsAt,
        Instant endsAt,
        Integer monthlyGameLimit,
        boolean grantedByAdmin,
        Instant revokedAt,
        Instant createdAt,
        UUID grantedBy,
        UUID revokedBy,
        RevokeReason revokeReason) {

    public boolean activeAt(Instant now) {
        return revokedAt == null && !startsAt.isAfter(now) && endsAt.isAfter(now);
    }
}
