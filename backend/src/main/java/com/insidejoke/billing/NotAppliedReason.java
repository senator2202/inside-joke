package com.insidejoke.billing;

/** Why a refund or chargeback the server received was not applied; stored in {@code webhook_event.reason}. */
public enum NotAppliedReason {
    AWAITING_APPROVAL,
    REJECTED,
    PARTIAL,
    CHARGEBACK_WARNING,
    CHARGEBACK_REVERSED,
    UNEXPECTED_STATUS
}
