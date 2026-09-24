package com.insidejoke.auth.dto;

import java.util.UUID;

public record ProfileDto(UUID id, String email, String displayName, String role, boolean googleLinked) {}
