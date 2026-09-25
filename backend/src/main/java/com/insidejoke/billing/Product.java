package com.insidejoke.billing;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/** What a host can buy. Prices are the USD list prices; Paddle localizes the displayed currency. */
public enum Product {
    PARTY_PASS(Duration.ofHours(24), null, 299),
    HOST_PASS(Duration.ofDays(365), 15, 1499);

    private final Duration validity;
    private final @Nullable Integer monthlyGameLimit;
    private final int priceUsdCents;

    Product(Duration validity, @Nullable Integer monthlyGameLimit, int priceUsdCents) {
        this.validity = validity;
        this.monthlyGameLimit = monthlyGameLimit;
        this.priceUsdCents = priceUsdCents;
    }

    public Duration validity() {
        return validity;
    }

    /** Games per calendar month (UTC), or null for unlimited. */
    public @Nullable Integer monthlyGameLimit() {
        return monthlyGameLimit;
    }

    public int priceUsdCents() {
        return priceUsdCents;
    }
}
