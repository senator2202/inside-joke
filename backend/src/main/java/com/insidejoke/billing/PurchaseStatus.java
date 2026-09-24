package com.insidejoke.billing;

/** Status of a purchase, stored in {@code purchase.status}. */
public enum PurchaseStatus {
    COMPLETED,
    /** Refunded or charged back; {@code purchase.refund_kind} says which. */
    REFUNDED
}
