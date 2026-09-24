package com.insidejoke.admin.dto;

public record RateLimitWindowDto(Long limit, Long remaining, String reset) {}
