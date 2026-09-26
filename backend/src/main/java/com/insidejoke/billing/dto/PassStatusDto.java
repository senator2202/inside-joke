package com.insidejoke.billing.dto;

import com.insidejoke.billing.Product;
import java.time.Instant;
import java.util.UUID;

/** A pass of the host: one running now, or one bought ahead that waits for the previous one of its kind to end. */
public record PassStatusDto(
        UUID id,
        Product type,
        Instant startsAt,
        Instant endsAt,
        Integer monthlyGameLimit,
        Integer gamesLeftThisMonth,
        boolean grantedByAdmin) {}
