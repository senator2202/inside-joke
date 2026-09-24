package com.insidejoke.analytics.dto;

import java.util.Map;

public record ClientEventRequestDto(String event, String anonymousId, Map<String, Object> properties) {}
