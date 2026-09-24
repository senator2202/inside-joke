package com.insidejoke.billing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the {@code Paddle-Signature} header: {@code ts=<unix seconds>;h1=<hex HMAC-SHA256 of "ts:rawBody">}.
 * Several h1 values may be present while a secret is being rotated; any match is enough. Old timestamps are refused
 * so a captured request can't be replayed later (event ids make replays harmless anyway).
 */
public final class PaddleSignatureUtils {

    private PaddleSignatureUtils() {}

    public static boolean valid(String header, byte[] body, String secret, Instant now, Duration tolerance) {
        if (header == null || header.isBlank() || secret == null || secret.isBlank()) {
            return false;
        }
        String ts = null;
        List<String> signatures = new ArrayList<>();
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (key.equals("ts")) {
                ts = value;
            } else if (key.equals("h1")) {
                signatures.add(value.toLowerCase());
            }
        }
        if (ts == null || signatures.isEmpty()) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(ts);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(tolerance) > 0) {
            return false;
        }
        byte[] expected = HexFormat.of().formatHex(hmac(secret, ts, body)).getBytes(StandardCharsets.US_ASCII);
        for (String candidate : signatures) {
            if (MessageDigest.isEqual(expected, candidate.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** Builds a header the way Paddle does (used by tests and local tooling). */
    public static String sign(byte[] body, String secret, Instant at) {
        String ts = Long.toString(at.getEpochSecond());
        return "ts=" + ts + ";h1=" + HexFormat.of().formatHex(hmac(secret, ts, body));
    }

    private static byte[] hmac(String secret, String ts, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((ts + ":").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
