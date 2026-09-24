package com.insidejoke.billing;

import java.util.Arrays;

/** Where a Paddle adjustment is in its approval. Unknown values from the wire map to {@code OTHER}. */
public enum AdjustmentStatus {
    PENDING_APPROVAL("pending_approval"),
    APPROVED("approved"),
    REJECTED("rejected"),
    REVERSED("reversed"),
    OTHER(null);

    private final String wire;

    AdjustmentStatus(String wire) {
        this.wire = wire;
    }

    /** The value as Paddle sends it. */
    public String wire() {
        return wire;
    }

    public static AdjustmentStatus fromWire(String value) {
        return Arrays.stream(values())
                .filter(v -> v.wire != null && v.wire.equals(value))
                .findFirst()
                .orElse(OTHER);
    }
}
