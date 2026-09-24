package com.insidejoke.billing;

import java.util.Arrays;

/** What a Paddle adjustment does to a transaction. Unknown values from the wire map to {@code OTHER}. */
public enum AdjustmentAction {
    REFUND("refund"),
    CHARGEBACK("chargeback"),
    CHARGEBACK_WARNING("chargeback_warning"),
    CHARGEBACK_REVERSE("chargeback_reverse"),
    CREDIT("credit"),
    CREDIT_REVERSE("credit_reverse"),
    OTHER(null);

    private final String wire;

    AdjustmentAction(String wire) {
        this.wire = wire;
    }

    /** The value as Paddle sends it. */
    public String wire() {
        return wire;
    }

    public static AdjustmentAction fromWire(String value) {
        return Arrays.stream(values())
                .filter(v -> v.wire != null && v.wire.equals(value))
                .findFirst()
                .orElse(OTHER);
    }
}
