package com.insidejoke.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * A host deleted their account. Published inside the deleting transaction; modules that keep data for the account
 * react to it (billing revokes passes, the game closes the host's rooms) without auth depending on them.
 */
public record AccountDeletedEvent(UUID userId, Instant at) {}
