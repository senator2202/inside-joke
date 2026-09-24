package com.insidejoke.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Providers, timeouts (blueprint 10.1: 8 s LLM, 5 s TTS) and the price table used to record every call's cost.
 * Prices are in micro-dollars so no floating point is involved.
 */
@ConfigurationProperties("app.ai")
public record AiProperties(
        Anthropic anthropic, Tts tts, @DefaultValue("true") boolean requireLlmModeration) {

    public record Anthropic(
            @DefaultValue("https://api.anthropic.com") String baseUrl,
            String apiKey,
            @DefaultValue("claude-haiku-4-5") String model,
            @DefaultValue("8s") Duration timeout,
            @DefaultValue("1") long inputMicrosPerToken,
            @DefaultValue("5") long outputMicrosPerToken) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** Any provider exposing the OpenAI-compatible POST /v1/audio/speech endpoint. */
    public record Tts(
            @DefaultValue("https://api.openai.com") String baseUrl,
            String apiKey,
            @DefaultValue("tts-1") String model,
            @DefaultValue("onyx") String voice,
            @DefaultValue("5s") Duration timeout,
            @DefaultValue("15") long microsPerChar) {
        public boolean enabled() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
