package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailValidationTest {

    @Test
    void acceptsOrdinaryAddresses() {
        assertThat(EmailLoginService.isValidEmail("masha@example.com")).isTrue();
        assertThat(EmailLoginService.isValidEmail(" Dima.K+party@mail.co.uk ")).isTrue();
    }

    @Test
    void rejectsMalformedAddresses() {
        assertThat(EmailLoginService.isValidEmail(null)).isFalse();
        assertThat(EmailLoginService.isValidEmail("")).isFalse();
        assertThat(EmailLoginService.isValidEmail("no-at-sign")).isFalse();
        assertThat(EmailLoginService.isValidEmail("two@@example.com")).isFalse();
        assertThat(EmailLoginService.isValidEmail("space in@example.com")).isFalse();
        assertThat(EmailLoginService.isValidEmail("user@localhost")).isFalse();
    }
}
