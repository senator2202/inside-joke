package com.insidejoke.billing;

import com.insidejoke.common.Money;
import java.time.Instant;
import java.util.UUID;

public record PurchaseEntity(
        UUID id,
        UUID userId,
        String providerTxnId,
        Product product,
        Money amount,
        PurchaseStatus status,
        Instant createdAt,
        Instant updatedAt) {}
