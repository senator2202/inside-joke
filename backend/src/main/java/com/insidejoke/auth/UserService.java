package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final Clock clock;

    public UserService(UserRepository users, ApplicationEventPublisher events, AppProperties props, Clock clock) {
        this.events = events;
        this.users = users;
        this.props = props;
        this.clock = clock;
    }

    /** Signs in or registers a host who proved ownership of the email address. */
    @Transactional
    public UserEntity loginWithEmail(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        return users.findActiveByEmail(email)
                .map(u -> users.recordLogin(u.id(), null, null, roleFor(email, u.role()), now))
                .orElseGet(() -> users.insert(email, null, null, roleFor(email, "HOST"), now));
    }

    /** Signs in or registers a host through Google; links an existing email account on first use. */
    @Transactional
    public UserEntity loginWithGoogle(String sub, String rawEmail, String name) {
        String email = normalizeEmail(rawEmail);
        Instant now = clock.instant();
        Optional<UserEntity> bySub = users.findActiveByGoogleSub(sub);
        if (bySub.isPresent()) {
            UserEntity u = bySub.get();
            return users.recordLogin(u.id(), name, sub, roleFor(u.email(), u.role()), now);
        }
        return users.findActiveByEmail(email)
                .map(u -> users.recordLogin(u.id(), name, sub, roleFor(email, u.role()), now))
                .orElseGet(() -> users.insert(email, name, sub, roleFor(email, "HOST"), now));
    }

    /** Active accounts whose email contains the fragment, newest first (admin search). */
    public List<UserEntity> search(String emailFragment, int limit) {
        return users.searchByEmail(emailFragment, limit);
    }

    public Optional<UserEntity> findActive(UUID id) {
        return users.findActiveById(id);
    }

    /** Anonymizes the account and ends its passes; purchases stay for accounting (blueprint 8). */
    @Transactional
    public void deleteAccount(UUID id) {
        Instant now = clock.instant();
        users.anonymize(id, now);
        events.publishEvent(new AccountDeletedEvent(id, now));
    }

    private String roleFor(String email, String current) {
        return props.isAdminEmail(email) ? "ADMIN" : current;
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
