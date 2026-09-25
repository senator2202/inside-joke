package com.insidejoke.mail;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class LogMailClientTest {

    @Test
    void worksWithTheLocalProfile() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThatCode(() -> new LogMailClient(local)).doesNotThrowAnyException();
    }

    @Test
    void refusesToStartAnywhereElse() {
        assertThatThrownBy(() -> new LogMailClient(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed only with the local profile");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new LogMailClient(prod)).isInstanceOf(IllegalStateException.class);
    }
}
