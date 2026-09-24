package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void formatsWithTheDecimalsTheCurrencyUses() {
        assertThat(Money.of(1499, "USD").format()).isEqualTo("14.99 USD");
        assertThat(Money.of(500, "JPY").format()).isEqualTo("500 JPY");
        assertThat(Money.of(1500, "BHD").format()).isEqualTo("1.500 BHD");
    }

    @Test
    void arithmeticStaysInOneCurrency() {
        Money paid = Money.of(1499, "EUR");
        assertThat(paid.minus(Money.of(499, "EUR"))).isEqualTo(Money.of(1000, "EUR"));
        assertThat(paid.plus(Money.of(1, "EUR")).minor()).isEqualTo(1500);
        assertThat(Money.of(1499, "EUR").isAtLeast(paid)).isTrue();
        assertThatThrownBy(() -> paid.plus(Money.of(1, "USD"))).hasMessage("Different currencies: EUR and USD");
    }

    @Test
    void theCurrencyIsAnIsoCode() {
        assertThat(Money.of(1, " usd ").currency()).isEqualTo("USD");
        assertThatThrownBy(() -> Money.of(1, "dollars")).isInstanceOf(IllegalArgumentException.class);
    }
}
