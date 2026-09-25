package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.Party;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** Admin access follows APP_ADMIN_EMAILS on every request, never the role remembered at sign-in (audit, item 16). */
@TestPropertySource(properties = "app.admin-emails=boss@example.com,deputy@example.com")
class AdminAccessIT extends AbstractIntegrationTest {

    @Autowired
    UserRepository users;

    @Test
    void anAdminLosesAccessOnTheirNextRequestOnceTheirAddressIsNotListed() {
        ApiClient deputy = Party.signIn(port, json, FAKE, "deputy@example.com");
        assertThat(deputy.get("/api/admin/settings").status()).isEqualTo(200);

        jdbc.sql("UPDATE app_user SET email = 'former-deputy@example.com' WHERE email = 'deputy@example.com'")
                .update();

        assertThat(deputy.get("/api/admin/settings").status())
                .as("same session, address no longer listed")
                .isEqualTo(403);
    }

    @Test
    void aFormerAdminStillMarkedAdminInTheDatabaseIsAHostAfterSigningIn() {
        String email = uniqueEmail("former-admin");
        users.insert(email, null, null, "ADMIN", clock.instant());

        ApiClient former = Party.signIn(port, json, FAKE, email);

        assertThat(former.get("/api/admin/settings").status()).isEqualTo(403);
        assertThat(former.get("/api/me").json().path("role").asString()).isEqualTo("HOST");
    }

    @Test
    void storedRolesFollowTheListAtStartup() {
        String stale = uniqueEmail("stale-admin");
        UserEntity staleAdmin = users.insert(stale, null, null, "ADMIN", clock.instant());
        UserEntity listed = users.findActiveByEmail("boss@example.com")
                .orElseGet(() -> users.insert("boss@example.com", null, null, "HOST", clock.instant()));
        users.setRole(listed.id(), "HOST");

        users.syncAdminRoles(List.of("boss@example.com", "deputy@example.com"));

        assertThat(users.findActiveById(staleAdmin.id()).orElseThrow().role()).isEqualTo("HOST");
        assertThat(users.findActiveById(listed.id()).orElseThrow().role()).isEqualTo("ADMIN");
    }
}
