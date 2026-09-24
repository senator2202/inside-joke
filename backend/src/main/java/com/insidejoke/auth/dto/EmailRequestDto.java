package com.insidejoke.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailRequestDto(
        @NotBlank @Size(max = 254) String email,
        @Size(max = 8) String language) {}
