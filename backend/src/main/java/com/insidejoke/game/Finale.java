package com.insidejoke.game;

import java.util.Map;

/** Personal titles and the closing speech (S8, P9). */
public record Finale(Map<String, String> titles, String speech, String source) {}
