package com.insidejoke.common;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount in the currency's smallest unit (cents for USD), with its ISO 4217 code. Adding or comparing amounts in
 * different currencies is an error, not a silent mix.
 */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        currency = currency.trim().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Not an ISO 4217 currency code: " + currency);
        }
    }

    public static Money of(long minor, String currency) {
        return new Money(minor, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(minor + other.minor, currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(minor - other.minor, currency);
    }

    public boolean isAtLeast(Money other) {
        requireSameCurrency(other);
        return minor >= other.minor;
    }

    /** "2.99 USD", with the number of decimals the currency uses. */
    public String format() {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException e) {
            digits = 2;
        }
        return BigDecimal.valueOf(minor, digits).toPlainString() + " " + currency;
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Different currencies: " + currency + " and " + other.currency);
        }
    }
}
