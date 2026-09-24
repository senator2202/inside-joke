package com.insidejoke.analytics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Product events (user flow section 8) sent to PostHog in batches every few seconds. The game never waits for
 * analytics: events are queued in memory, and on failure or overflow they are dropped.
 */
@Service
public class AnalyticsService {

    public record Event(String event, String distinctId, Map<String, Object> properties, Instant at) {}

    static final int MAX_QUEUE = 10_000;
    static final int MAX_BATCH = 500;
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final AnalyticsProperties props;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final ConcurrentLinkedQueue<Event> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();

    public AnalyticsService(AnalyticsProperties props, HttpClient http, JsonMapper json, Clock clock) {
        this.props = props;
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    public void track(String event, String distinctId, Map<String, Object> properties) {
        if (size.incrementAndGet() > MAX_QUEUE) {
            size.decrementAndGet();
            return;
        }
        queue.add(new Event(
                event, distinctId == null ? "anonymous" : distinctId, Map.copyOf(properties), clock.instant()));
    }

    public void track(String event, String distinctId) {
        track(event, distinctId, Map.of());
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void flush() {
        while (true) {
            List<Event> batch = new ArrayList<>();
            Event e;
            while (batch.size() < MAX_BATCH && (e = queue.poll()) != null) {
                size.decrementAndGet();
                batch.add(e);
            }
            if (batch.isEmpty()) {
                return;
            }
            if (!props.enabled()) {
                log.debug("Analytics disabled, dropped {} events", batch.size());
                continue;
            }
            send(batch);
        }
    }

    private void send(List<Event> batch) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Event e : batch) {
            Map<String, Object> properties = new HashMap<>(e.properties());
            properties.put("$lib", "inside-joke-server");
            items.add(Map.of(
                    "event",
                    e.event(),
                    "distinct_id",
                    e.distinctId(),
                    "properties",
                    properties,
                    "timestamp",
                    e.at().toString()));
        }
        String body = json.writeValueAsString(Map.of("api_key", props.apiKey(), "batch", items));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.host().replaceAll("/+$", "") + "/batch/"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                log.warn("PostHog rejected {} events: HTTP {}", batch.size(), response.statusCode());
            }
        } catch (IOException ex) {
            log.warn("PostHog unreachable, dropped {} events: {}", batch.size(), ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
