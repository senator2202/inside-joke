package com.insidejoke.ai;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Text-to-speech through an OpenAI-compatible POST /v1/audio/speech endpoint, returning MP3 bytes. */
@Component
public class TtsClient {

    public record Speech(byte[] mp3, int latencyMs) {}

    private final AiProperties.Tts props;
    private final HttpClient http;
    private final JsonMapper json;

    public TtsClient(AiProperties props, HttpClient http, JsonMapper json) {
        this.props = props.tts();
        this.http = http;
        this.json = json;
    }

    public boolean enabled() {
        return props.enabled();
    }

    public String model() {
        return props.model();
    }

    public String voice() {
        return props.voice();
    }

    public Speech synthesize(String text) throws AiCallException {
        String body = json.writeValueAsString(
                Map.of("model", props.model(), "voice", props.voice(), "input", text, "response_format", "mp3"));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(props.baseUrl().replaceAll("/+$", "") + "/v1/audio/speech"))
                .timeout(props.timeout())
                .header("Authorization", "Bearer " + props.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int latency = AnthropicClient.elapsed(started);
            if (response.statusCode() != 200 || response.body().length == 0) {
                throw new AiCallException(AiOutcome.ERROR, latency, "TTS HTTP " + response.statusCode());
            }
            return new Speech(response.body(), latency);
        } catch (HttpTimeoutException e) {
            throw new AiCallException(AiOutcome.TIMEOUT, AnthropicClient.elapsed(started), "TTS timed out");
        } catch (IOException e) {
            throw new AiCallException(
                    AiOutcome.ERROR, AnthropicClient.elapsed(started), "TTS unreachable: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiCallException(AiOutcome.ERROR, AnthropicClient.elapsed(started), "Interrupted");
        }
    }

    public long costMicros(int chars) {
        return chars * props.microsPerChar();
    }
}
