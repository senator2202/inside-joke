package com.insidejoke.billing;

/** Status of a pass in the admin pass log, at the moment of the query: refunded beats revoked beats the time window. */
public enum PassLogStatus {
    ACTIVE,
    UPCOMING,
    EXPIRED,
    REVOKED,
    REFUNDED
}
