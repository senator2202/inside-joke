package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.common.AppProperties;
import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.FakeGoogle;
import com.insidejoke.support.FakeResend;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GoogleLoginIT extends AbstractIntegrationTest {

    @Autowired
    AppProperties appProps;

    private FakeGoogle google;

    @BeforeEach
    void provider() {
        google = new FakeGoogle().install(FAKE);
    }

    @Test
    void authorizationCodeFlowCreatesAccountAndSession() {
        ApiClient client = client();
        String email = uniqueEmail("g");
        String sub = "g-" + UUID.randomUUID();

        ApiClient.Resp callback = google.login(client, new FakeGoogle.Identity(sub, email, true, "Masha K"));
        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location())
                .as("the public URL, whichever host received Google's callback")
                .hasValue(appProps.publicUrl() + "/auth/callback?provider=google");
        assertThat(FAKE.requests("POST", "/google/token")).hasSize(1);
        assertThat(FAKE.requests("POST", "/google/token").getFirst().header("Authorization"))
                .startsWith("Basic ");

        ApiClient.Resp me = client.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(me.json().path("displayName").asString()).isEqualTo("Masha K");
        assertThat(me.json().path("googleLinked").asBoolean()).isTrue();
    }

    @Test
    void googleLinksToExistingEmailAccount() {
        FakeResend.accept(FAKE);
        ApiClient emailClient = client();
        String email = uniqueEmail("both");
        emailClient.post("/api/auth/magic-link", Map.of("email", email));
        emailClient.post("/api/auth/email-code/verify", Map.of("email", email, "code", FakeResend.lastCode(FAKE)));
        String emailUserId = emailClient.get("/api/me").json().path("id").asString();

        ApiClient googleClient = client();
        google.login(googleClient, new FakeGoogle.Identity("g-" + UUID.randomUUID(), email, true, "Both Ways"));
        assertThat(googleClient.get("/api/me").json().path("id").asString()).isEqualTo(emailUserId);
    }

    @Test
    void unverifiedGoogleEmailIsRefused() {
        ApiClient client = client();
        ApiClient.Resp callback = google.login(
                client, new FakeGoogle.Identity("g-" + UUID.randomUUID(), uniqueEmail("unverified"), false, "Nope"));
        assertThat(callback.location()).hasValue(appProps.publicUrl() + "/login?error=google_email");
        assertThat(client.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void authConfigAdvertisesGoogle() {
        assertThat(client().get("/api/auth/config").json().path("googleEnabled").asBoolean())
                .isTrue();
    }
}
