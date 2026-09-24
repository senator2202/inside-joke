package com.insidejoke.billing.dto;

import java.util.List;
import java.util.Map;

public record CheckoutConfigDto(
        boolean available,
        String environment,
        String clientToken,
        List<OfferDto> offers,
        String email,
        Map<String, String> customData,
        String supportEmail) {}
