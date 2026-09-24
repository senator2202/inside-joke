package com.insidejoke.billing;

/** Why a pass stopped before its end: stored with the pass and shown in the admin pass log. */
public enum RevokeReason {
    /** An approved refund covered the purchase. */
    REFUND,
    /** The customer's bank reversed the payment. */
    CHARGEBACK,
    /** An admin revoked it by hand. */
    ADMIN,
    /** The host deleted their account. */
    ACCOUNT_DELETED
}
