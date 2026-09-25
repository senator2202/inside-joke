package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.insidejoke.common.AppProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The admin list decides, on every request; the role saved in the session does not (docs/AUDIT.md, item 16). */
class AdminAccessServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final AdminAccessService admins = new AdminAccessService(
            users, new AppProperties("http://localhost", List.of(), List.of("boss@example.com"), null));

    private AppPrincipal signedIn(String email, String roleInSession) {
        UUID id = UUID.randomUUID();
        when(users.findActiveById(id))
                .thenReturn(Optional.of(
                        new UserEntity(id, email, null, null, roleInSession, Instant.EPOCH, Instant.EPOCH)));
        return new AppPrincipal(id, email, roleInSession);
    }

    @Test
    void aListedAddressIsAnAdmin() {
        assertThat(admins.isAdmin(signedIn("boss@example.com", "ADMIN"))).isTrue();
    }

    @Test
    void anAdminRoleInTheSessionGrantsNothingOnceTheAddressIsNotListed() {
        assertThat(admins.isAdmin(signedIn("former-boss@example.com", "ADMIN"))).isFalse();
    }

    @Test
    void aListedAddressIsAnAdminWhateverTheSessionSays() {
        assertThat(admins.isAdmin(signedIn("boss@example.com", "HOST"))).isTrue();
    }

    @Test
    void theAccountsCurrentAddressCountsNotTheOneInTheSession() {
        UUID id = UUID.randomUUID();
        when(users.findActiveById(id))
                .thenReturn(Optional.of(new UserEntity(
                        id, "someone-else@example.com", null, null, "ADMIN", Instant.EPOCH, Instant.EPOCH)));
        assertThat(admins.isAdmin(new AppPrincipal(id, "boss@example.com", "ADMIN")))
                .isFalse();
    }

    @Test
    void aDeletedAccountOrNoSessionIsNotAnAdmin() {
        UUID id = UUID.randomUUID();
        when(users.findActiveById(id)).thenReturn(Optional.empty());
        assertThat(admins.isAdmin(new AppPrincipal(id, "boss@example.com", "ADMIN")))
                .isFalse();
        assertThat(admins.isAdmin(null)).isFalse();
    }
}
