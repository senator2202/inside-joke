package com.insidejoke.admin.dto;

public record CurrencyRevenueDto(String currency, long grossMinor, long refundedMinor, long netMinor) {}
