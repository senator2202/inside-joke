package com.insidejoke.analytics;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** PostHog project key and ingestion host. Without a key, events are counted in the log and dropped. */
@ConfigurationProperties("app.posthog")
public record AnalyticsProperties(
        String apiKey,
        @DefaultValue("https://eu.i.posthog.com") String host) {

    public boolean enabled() {
        return apiKey != null && !apiKey.isBlank();
    }
}
