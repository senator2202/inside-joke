package com.insidejoke.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Google OpenID Connect client. Endpoints are configurable so tests can point them at a local server. */
@ConfigurationProperties("app.google")
public record GoogleProperties(
        String clientId,
        String clientSecret,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri,
        String userInfoUri,
        String issuerUri) {

    public GoogleProperties {
        authorizationUri = orDefault(authorizationUri, "https://accounts.google.com/o/oauth2/v2/auth");
        tokenUri = orDefault(tokenUri, "https://oauth2.googleapis.com/token");
        jwkSetUri = orDefault(jwkSetUri, "https://www.googleapis.com/oauth2/v3/certs");
        userInfoUri = orDefault(userInfoUri, "https://openidconnect.googleapis.com/v1/userinfo");
        issuerUri = orDefault(issuerUri, "https://accounts.google.com");
    }

    public boolean enabled() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
