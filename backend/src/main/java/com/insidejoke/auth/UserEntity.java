package com.insidejoke.auth;

import java.time.Instant;
import java.util.UUID;

public record UserEntity(
        UUID id,
        String email,
        String displayName,
        String googleSub,
        String role,
        Instant createdAt,
        Instant lastLoginAt) {

    public AppPrincipal toPrincipal() {
        return new AppPrincipal(id, email, role);
    }
}
