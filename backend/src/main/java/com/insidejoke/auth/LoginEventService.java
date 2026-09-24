package com.insidejoke.auth;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Publishes {@link HostLoggedIn} so other modules (analytics) can react without depending on auth internals. */
@Service
public class LoginEventService {

    /** A host completed sign-in. */
    public record HostLoggedIn(UUID userId, String method) {}

    private final ApplicationEventPublisher publisher;

    public LoginEventService(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void loggedIn(UserEntity user, String method) {
        publisher.publishEvent(new HostLoggedIn(user.id(), method));
    }
}
