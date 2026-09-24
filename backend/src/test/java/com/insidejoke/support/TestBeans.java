package com.insidejoke.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Beans shared by every integration test; kept identical so all tests reuse one Spring context. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    @Bean
    @Primary
    public TestClock testClock() {
        return new TestClock();
    }
}
