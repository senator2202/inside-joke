package com.insidejoke.billing.dto;

/** Processed (with what result), or kept for a manual look (with why). */
public record WebhookOutcomeDto(boolean processed, String result) {}
