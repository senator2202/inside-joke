package com.insidejoke.ai;

import java.io.Serial;

/** A provider call that failed; {@link #outcome} says how, for the ai_call record. */
public class AiCallException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final AiOutcome outcome;
    private final int latencyMs;

    public AiCallException(AiOutcome outcome, int latencyMs, String message) {
        super(message);
        this.outcome = outcome;
        this.latencyMs = latencyMs;
    }

    public AiOutcome outcome() {
        return outcome;
    }

    public int latencyMs() {
        return latencyMs;
    }
}
