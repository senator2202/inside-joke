package com.insidejoke.admin.dto;

import java.time.Instant;

/** A payment event that arrived but could not be applied. */
public record FailedWebhookDto(
        String provider, String eventId, String eventType, Instant receivedAt, String lastError) {}
