package com.insidejoke.billing;

import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Paddle Billing: the client-side token for the Paddle.js overlay, the webhook secret, and the price ids of the two
 * products. Without a client token and both prices the pay button is disabled; games on passes already bought keep working.
 */
@ConfigurationProperties("app.paddle")
public record PaddleProperties(
        @DefaultValue("sandbox") String environment,
        String clientToken,
        String webhookSecret,
        @DefaultValue Prices prices,
        @DefaultValue("5m") Duration signatureTolerance,
        @DefaultValue("support@insidejoke.app") String supportEmail) {

    public record Prices(String partyPass, String hostPass) {}

    public boolean checkoutEnabled() {
        return present(clientToken) && present(prices.partyPass()) && present(prices.hostPass());
    }

    public boolean webhooksEnabled() {
        return present(webhookSecret);
    }

    public String priceFor(Product product) {
        return product == Product.PARTY_PASS ? prices.partyPass() : prices.hostPass();
    }

    /** The product is decided by the price that was paid, never by anything the browser sent. */
    public Optional<Product> productFor(String priceId) {
        if (!present(priceId)) {
            return Optional.empty();
        }
        for (Product p : Product.values()) {
            if (priceId.equals(priceFor(p))) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
