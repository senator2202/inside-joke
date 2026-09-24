package com.insidejoke.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PaddleSignatureUtilsTest {

    private static final byte[] BODY = "{\"event_id\":\"evt_1\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-09-21T20:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void matchesAnIndependentlyComputedHmac() {
        // python3: hmac.new(b"whsec_known", b'1790000000:{"event_id":"evt_1"}', hashlib.sha256).hexdigest()
        String expected = "39b446a7192270aa89b201f50536f245808ed592141eff0c9662844277c2d111";
        Instant at = Instant.ofEpochSecond(1_790_000_000L);
        assertThat(PaddleSignatureUtils.sign(BODY, "whsec_known", at)).isEqualTo("ts=1790000000;h1=" + expected);
        assertThat(PaddleSignatureUtils.valid("ts=1790000000;h1=" + expected, BODY, "whsec_known", at, TOLERANCE))
                .isTrue();
    }

    @Test
    void acceptsItsOwnSignatureAndRejectsTampering() {
        String header = PaddleSignatureUtils.sign(BODY, "s3cret", NOW);
        assertThat(PaddleSignatureUtils.valid(header, BODY, "s3cret", NOW, TOLERANCE))
                .isTrue();
        assertThat(PaddleSignatureUtils.valid(
                        header, "{\"event_id\":\"evt_2\"}".getBytes(StandardCharsets.UTF_8), "s3cret", NOW, TOLERANCE))
                .isFalse();
        assertThat(PaddleSignatureUtils.valid(header, BODY, "other", NOW, TOLERANCE))
                .isFalse();
        assertThat(PaddleSignatureUtils.valid(
                        header.toUpperCase().replace("TS=", "ts=").replace("H1=", "h1="),
                        BODY,
                        "s3cret",
                        NOW,
                        TOLERANCE))
                .as("hex case does not matter")
                .isTrue();
    }

    @Test
    void rejectsOldOrMalformedHeaders() {
        String old = PaddleSignatureUtils.sign(BODY, "s3cret", NOW.minus(Duration.ofMinutes(6)));
        assertThat(PaddleSignatureUtils.valid(old, BODY, "s3cret", NOW, TOLERANCE))
                .isFalse();
        assertThat(PaddleSignatureUtils.valid(null, BODY, "s3cret", NOW, TOLERANCE))
                .isFalse();
        assertThat(PaddleSignatureUtils.valid("h1=abc", BODY, "s3cret", NOW, TOLERANCE))
                .isFalse();
        assertThat(PaddleSignatureUtils.valid("ts=abc;h1=abc", BODY, "s3cret", NOW, TOLERANCE))
                .isFalse();
        String anyHeader = "ts=" + NOW.getEpochSecond() + ";h1=" + "a".repeat(64);
        assertThat(PaddleSignatureUtils.valid(anyHeader, BODY, "", NOW, TOLERANCE))
                .as("no secret configured")
                .isFalse();
    }

    @Test
    void anyMatchingSignatureCountsDuringSecretRotation() {
        String fresh = PaddleSignatureUtils.sign(BODY, "new-secret", NOW);
        String h1 = fresh.substring(fresh.indexOf("h1="));
        String header = "ts=" + NOW.getEpochSecond() + ";h1=" + "0".repeat(64) + ";" + h1;
        assertThat(PaddleSignatureUtils.valid(header, BODY, "new-secret", NOW, TOLERANCE))
                .isTrue();
    }
}
