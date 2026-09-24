package com.insidejoke.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal client for the Anthropic Messages API (POST /v1/messages). */
@Component
public class AnthropicClient {

    public record Completion(String text, int inputTokens, int outputTokens, String model, int latencyMs) {}

    /**
     * Rate limits Anthropic reported with its latest response of any status: the {@code anthropic-ratelimit-*} headers
     * (and {@code retry-after}), lower-cased. Kept in memory only; shown in the admin AI tab.
     */
    public record RateLimits(Instant capturedAt, int httpStatus, Map<String, String> headers) {}

    static final String API_VERSION = "2023-06-01";

    private final AiProperties.Anthropic props;
    private final HttpClient http;
    private final JsonMapper json;
    private final Clock clock;
    private final AtomicReference<RateLimits> rateLimits = new AtomicReference<>();

    public AnthropicClient(AiProperties props, HttpClient http, JsonMapper json, Clock clock) {
        this.props = props.anthropic();
        this.http = http;
        this.json = json;
        this.clock = clock;
    }

    /** The rate limits from the latest response, if Anthropic has answered since the server started. */
    public Optional<RateLimits> rateLimits() {
        return Optional.ofNullable(rateLimits.get());
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String model() {
        return props.model();
    }

    public Completion complete(String system, String user, int maxTokens) throws AiCallException {
        String body = json.writeValueAsString(Map.of(
                "model",
                props.model(),
                "max_tokens",
                maxTokens,
                "system",
                system,
                "messages",
                List.of(Map.of("role", "user", "content", user))));
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + "/v1/messages"))
                .timeout(props.timeout())
                .header("x-api-key", props.apiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long started = System.nanoTime();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new AiCallException(AiOutcome.TIMEOUT, elapsed(started), "Anthropic timed out");
        } catch (IOException e) {
            throw new AiCallException(AiOutcome.ERROR, elapsed(started), "Anthropic unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(AiOutcome.ERROR, elapsed(started), "Interrupted");
        }
        int latency = elapsed(started);
        captureRateLimits(response);
        if (response.statusCode() != 200) {
            throw new AiCallException(
                    AiOutcome.ERROR, latency, "Anthropic HTTP " + response.statusCode() + errorDetail(response.body()));
        }
        JsonNode root = json.readTree(response.body());
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        JsonNode usage = root.path("usage");
        return new Completion(
                text.toString(),
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                root.path("model").asString(props.model()),
                latency);
    }

    public long costMicros(int inputTokens, int outputTokens) {
        return inputTokens * props.inputMicrosPerToken() + outputTokens * props.outputMicrosPerToken();
    }

    static int elapsed(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }

    private void captureRateLimits(HttpResponse<?> response) {
        Map<String, String> limits = new TreeMap<>();
        response.headers().map().forEach((name, values) -> {
            String n = name.toLowerCase(Locale.ROOT);
            if ((n.startsWith("anthropic-ratelimit-") || n.equals("retry-after")) && !values.isEmpty()) {
                limits.put(n, values.getFirst());
            }
        });
        if (!limits.isEmpty()) {
            rateLimits.set(new RateLimits(clock.instant(), response.statusCode(), Map.copyOf(limits)));
        }
    }

    /** ": invalid_request_error: Your credit balance is too low…" from an Anthropic error body, or "" if there is none. */
    private String errorDetail(String body) {
        try {
            JsonNode error = json.readTree(body).path("error");
            String type = error.path("type").asString("");
            String message = error.path("message").asString("");
            String detail = (type.isEmpty() ? "" : type + ": ") + message;
            if (detail.isBlank()) {
                return "";
            }
            return ": " + (detail.length() > 300 ? detail.substring(0, 300) + "…" : detail);
        } catch (RuntimeException e) {
            return "";
        }
    }
}
