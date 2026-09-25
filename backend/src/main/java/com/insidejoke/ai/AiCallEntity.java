package com.insidejoke.ai;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One row of ai_call: what a provider call cost and how it went. */
public record AiCallEntity(
        @Nullable UUID gameSessionId,
        AiPurpose purpose,
        String provider,
        String model,
        @Nullable String promptVersion,
        @Nullable Integer inputTokens,
        @Nullable Integer outputTokens,
        @Nullable Integer ttsChars,
        long costMicros,
        int latencyMs,
        AiOutcome outcome,
        boolean freeGame,
        @Nullable String error) {

    /** A call with nothing to report beyond its outcome. */
    public AiCallEntity(
            UUID gameSessionId,
            AiPurpose purpose,
            String provider,
            String model,
            String promptVersion,
            Integer inputTokens,
            Integer outputTokens,
            Integer ttsChars,
            long costMicros,
            int latencyMs,
            AiOutcome outcome,
            boolean freeGame) {
        this(
                gameSessionId,
                purpose,
                provider,
                model,
                promptVersion,
                inputTokens,
                outputTokens,
                ttsChars,
                costMicros,
                latencyMs,
                outcome,
                freeGame,
                null);
    }
}
