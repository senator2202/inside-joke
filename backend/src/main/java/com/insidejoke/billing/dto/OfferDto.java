package com.insidejoke.billing.dto;

import com.insidejoke.billing.Product;

public record OfferDto(
        Product product, String priceId, int listPriceUsdCents, Integer monthlyGameLimit, long validityHours) {}
