package com.insidejoke.common;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-wide settings.
 *
 * @param publicUrl       external URL of the app, used for links, QR codes and the WebSocket Origin check
 * @param allowedOrigins  extra origins allowed to open WebSockets (for example the Vite dev server)
 * @param adminEmails     emails that receive the ADMIN role on sign-in
 * @param contentSecurityPolicy value of the Content-Security-Policy header for every response
 */
@ConfigurationProperties("app")
public record AppProperties(
        String publicUrl, List<String> allowedOrigins, List<String> adminEmails, String contentSecurityPolicy) {

    public AppProperties {
        publicUrl = publicUrl == null ? "http://localhost:8080" : stripSlash(publicUrl);
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .filter(s -> !s.isBlank())
                        .map(AppProperties::stripSlash)
                        .toList();
        adminEmails = adminEmails == null
                ? List.of()
                : adminEmails.stream()
                        .filter(s -> !s.isBlank())
                        .map(s -> s.trim().toLowerCase(Locale.ROOT))
                        .toList();
    }

    public boolean isAdminEmail(String email) {
        return email != null && adminEmails.contains(email.trim().toLowerCase(Locale.ROOT));
    }

    public List<String> webSocketOrigins() {
        return Stream.concat(Stream.of(publicUrl), allowedOrigins.stream())
                .distinct()
                .toList();
    }

    private static String stripSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
