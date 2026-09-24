package com.insidejoke.mail;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transactional email settings.
 *
 * @param provider "resend" sends through the Resend HTTP API; "smtp" through any SMTP server (Gmail, Yandex, your own);
 *                 "log" writes the message to the log (local development only)
 * @param from     sender as {@code Name <address>}; with Gmail it must be the account's own address or a verified alias
 */
@ConfigurationProperties("app.mail")
public record MailProperties(String provider, String from, Resend resend, Smtp smtp) {

    public static final Set<String> PROVIDERS = Set.of("log", "resend", "smtp");

    public record Resend(String baseUrl, String apiKey) {}

    /** How the connection to the SMTP server is protected. */
    public enum Security {
        /** Plain connection upgraded with STARTTLS, which is required (port 587, the usual choice). */
        STARTTLS,
        /** TLS from the first byte (port 465). */
        SSL,
        /** No encryption: only for a server on the same machine or a test server. */
        NONE
    }

    /**
     * @param host     SMTP server, e.g. smtp.gmail.com; empty means not configured
     * @param port     587 for STARTTLS, 465 for SSL when not set
     * @param username login; empty means the server doesn't ask for one
     * @param password password (for Gmail, an app password)
     * @param timeout  connect, read and write timeout
     */
    public record Smtp(
            String host, Integer port, String username, String password, Security security, Duration timeout) {

        public Smtp {
            host = host == null ? "" : host.trim();
            security = security == null ? Security.STARTTLS : security;
            port = port == null ? (security == Security.SSL ? 465 : 587) : port;
            username = username == null ? "" : username.trim();
            password = password == null ? "" : password;
            timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        }

        public boolean configured() {
            return !host.isEmpty();
        }

        public boolean authenticates() {
            return !username.isEmpty();
        }
    }

    public MailProperties {
        provider = provider == null || provider.isBlank()
                ? "resend"
                : provider.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new IllegalArgumentException("MAIL_PROVIDER must be log, resend or smtp, not '" + provider + "'");
        }
        from = from == null ? "Inside Joke <login@insidejoke.app>" : from;
        resend = resend == null ? new Resend("https://api.resend.com", "") : resend;
        smtp = smtp == null ? new Smtp(null, null, null, null, null, null) : smtp;
    }
}
