package com.insidejoke.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CodeRequestDto(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 12) String code) {}
