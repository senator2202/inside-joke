package com.insidejoke.admin.dto;

import com.insidejoke.billing.dto.PassStatusDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminUserDto(
        UUID id,
        String email,
        String displayName,
        String role,
        boolean googleLinked,
        Instant createdAt,
        Instant lastLoginAt,
        int games,
        List<PassStatusDto> passes) {}
