package com.insidejoke.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LinkRequestDto(@NotBlank @Size(max = 100) String token) {}
