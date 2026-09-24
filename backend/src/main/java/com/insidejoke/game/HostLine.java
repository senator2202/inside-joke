package com.insidejoke.game;

import java.util.Set;

/** What the AI host is saying right now. Players named in it may ask to skip it. */
public record HostLine(String id, String text, String audioId, Set<String> aboutPlayerIds, boolean skipped) {

    public HostLine withAudio(String audio) {
        return new HostLine(id, text, audio, aboutPlayerIds, skipped);
    }
}
