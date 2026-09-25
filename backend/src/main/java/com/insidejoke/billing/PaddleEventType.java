package com.insidejoke.billing;

import java.util.Arrays;

/** Paddle webhook event types this app reads. Unknown values from the wire map to {@code OTHER}. */
public enum PaddleEventType {
    TRANSACTION_COMPLETED("transaction.completed"),
    ADJUSTMENT_CREATED("adjustment.created"),
    ADJUSTMENT_UPDATED("adjustment.updated"),
    OTHER(null);

    private final String wire;

    PaddleEventType(String wire) {
        this.wire = wire;
    }

    public static PaddleEventType fromWire(String value) {
        return Arrays.stream(values())
                .filter(v -> v.wire != null && v.wire.equals(value))
                .findFirst()
                .orElse(OTHER);
    }
}
