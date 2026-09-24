package com.insidejoke.common.dto;

import java.util.Map;

public record ErrorDetailDto(String code, String message, Map<String, Object> details) {}
