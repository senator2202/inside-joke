package com.insidejoke.billing.dto;

import com.insidejoke.billing.Product;
import java.time.Instant;
import java.util.UUID;

public record PassStatusDto(
        UUID id,
        Product type,
        Instant endsAt,
        Integer monthlyGameLimit,
        Integer gamesLeftThisMonth,
        boolean grantedByAdmin) {}
