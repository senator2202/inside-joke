package com.insidejoke.ai;

/** Who served an AI call; stored in {@code ai_call.provider}. */
public enum AiProvider {
    ANTHROPIC("anthropic"),
    TTS("tts");

    private final String wire;

    AiProvider(String wire) {
        this.wire = wire;
    }

    /** The value stored in the database. */
    public String wire() {
        return wire;
    }
}
