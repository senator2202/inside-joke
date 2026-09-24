package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokenUtilsTest {

    @Test
    void randomTokensAreUrlSafeAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = TokenUtils.random(16);
            assertThat(t).matches("[A-Za-z0-9_-]{22}");
            assertThat(seen.add(t)).isTrue();
        }
    }

    @Test
    void digitsHaveRequestedLength() {
        assertThat(TokenUtils.digits(6)).matches("\\d{6}");
    }

    @Test
    void sha256IsStable() {
        assertThat(TokenUtils.constantTimeEquals(TokenUtils.sha256("abc"), TokenUtils.sha256("abc")))
                .isTrue();
        assertThat(TokenUtils.constantTimeEquals(TokenUtils.sha256("abc"), TokenUtils.sha256("abd")))
                .isFalse();
    }
}
