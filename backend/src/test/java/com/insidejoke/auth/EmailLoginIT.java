package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.insidejoke.support.AbstractIntegrationTest;
import com.insidejoke.support.ApiClient;
import com.insidejoke.support.FakeHttp;
import com.insidejoke.support.FakeResend;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmailLoginIT extends AbstractIntegrationTest {

    @BeforeEach
    void mail() {
        FakeResend.accept(FAKE);
    }

    @Test
    void codeFromEmailSignsInAndLogoutEndsSession() {
        ApiClient client = client();
        String email = uniqueEmail("host");
        assertThat(client.get("/api/me").status()).isEqualTo(401);

        ApiClient.Resp sent = client.post("/api/auth/magic-link", Map.of("email", email));
        assertThat(sent.status()).isEqualTo(202);
        FakeHttp.Recorded mail = FAKE.requests("POST", "/resend/emails").getFirst();
        assertThat(mail.header("Authorization")).isEqualTo("Bearer test-resend-key");
        assertThat(mail.body()).contains(email.toLowerCase());

        String code = FakeResend.lastCode(FAKE);
        String wrong = code.equals("000000") ? "111111" : "000000";
        ApiClient.Resp bad = client.post("/api/auth/email-code/verify", Map.of("email", email, "code", wrong));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.errorCode()).isEqualTo("CODE_INVALID");
        assertThat(bad.json().path("error").path("details").path("attemptsLeft").asInt())
                .isEqualTo(4);

        ApiClient.Resp ok = client.post("/api/auth/email-code/verify", Map.of("email", email, "code", code));
        assertThat(ok.status()).isEqualTo(200);

        ApiClient.Resp me = client.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().path("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(me.json().path("role").asString()).isEqualTo("HOST");

        assertThat(client.post("/api/auth/logout", null).status()).isEqualTo(204);
        assertThat(client.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void codeIsSingleUse() {
        ApiClient client = client();
        String email = uniqueEmail("single");
        client.post("/api/auth/magic-link", Map.of("email", email));
        String code = FakeResend.lastCode(FAKE);
        assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", code))
                        .status())
                .isEqualTo(200);
        ApiClient other = client();
        ApiClient.Resp again = other.post("/api/auth/email-code/verify", Map.of("email", email, "code", code));
        assertThat(again.errorCode()).isEqualTo("CODE_EXPIRED");
    }

    @Test
    void fiveWrongCodesLockTheChallenge() {
        ApiClient client = client();
        String email = uniqueEmail("locked");
        client.post("/api/auth/magic-link", Map.of("email", email));
        String code = FakeResend.lastCode(FAKE);
        String wrong = code.equals("123456") ? "654321" : "123456";
        for (int i = 0; i < 4; i++) {
            assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", wrong))
                            .errorCode())
                    .isEqualTo("CODE_INVALID");
        }
        assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", wrong))
                        .errorCode())
                .isEqualTo("CODE_LOCKED");
        assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", code))
                        .errorCode())
                .isEqualTo("CODE_LOCKED");

        client.post("/api/auth/magic-link", Map.of("email", email));
        String fresh = FakeResend.lastCode(FAKE);
        assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", fresh))
                        .status())
                .isEqualTo(200);
    }

    @Test
    void expiredCodeIsRejected() {
        ApiClient client = client();
        String email = uniqueEmail("expired");
        client.post("/api/auth/magic-link", Map.of("email", email));
        String code = FakeResend.lastCode(FAKE);
        jdbc.sql("UPDATE magic_link_token SET expires_at = now() - interval '1 minute' WHERE lower(email) = lower(?)")
                .param(email)
                .update();
        assertThat(client.post("/api/auth/email-code/verify", Map.of("email", email, "code", code))
                        .errorCode())
                .isEqualTo("CODE_EXPIRED");
    }

    @Test
    void magicLinkSignsInOnce() {
        ApiClient client = client();
        String email = uniqueEmail("link");
        client.post("/api/auth/magic-link", Map.of("email", email));
        String token = FakeResend.lastLinkToken(FAKE);

        assertThat(client.post("/api/auth/magic-link/verify", Map.of("token", token))
                        .status())
                .isEqualTo(200);
        assertThat(client.get("/api/me").json().path("email").asString()).isEqualTo(email.toLowerCase());

        ApiClient.Resp reuse = client().post("/api/auth/magic-link/verify", Map.of("token", token));
        assertThat(reuse.errorCode()).isEqualTo("LINK_INVALID");
    }

    @Test
    void newCodeSupersedesOldOne() {
        ApiClient client = client();
        String email = uniqueEmail("supersede");
        client.post("/api/auth/magic-link", Map.of("email", email));
        String firstToken = FakeResend.lastLinkToken(FAKE);
        client.post("/api/auth/magic-link", Map.of("email", email));
        assertThat(client.post("/api/auth/magic-link/verify", Map.of("token", firstToken))
                        .errorCode())
                .isEqualTo("LINK_INVALID");
    }

    @Test
    void emailProviderFailureIsReported() {
        FakeResend.fail(FAKE);
        ApiClient.Resp resp = client().post("/api/auth/magic-link", Map.of("email", uniqueEmail("down")));
        assertThat(resp.status()).isEqualTo(502);
        assertThat(resp.errorCode()).isEqualTo("EMAIL_SEND_FAILED");
    }

    @Test
    void perAddressLimitIsFivePerHour() {
        ApiClient client = client();
        String email = uniqueEmail("spam");
        for (int i = 0; i < 5; i++) {
            assertThat(client.post("/api/auth/magic-link", Map.of("email", email))
                            .status())
                    .isEqualTo(202);
        }
        ApiClient.Resp limited = client.post("/api/auth/magic-link", Map.of("email", email));
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.errorCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void invalidEmailIsAValidationError() {
        ApiClient.Resp resp = client().post("/api/auth/magic-link", Map.of("email", "not-an-email"));
        assertThat(resp.status()).isEqualTo(400);
        assertThat(resp.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(FAKE.requests("POST", "/resend/emails")).isEmpty();
    }

    @Test
    void mutatingRequestsRequireCsrfToken() {
        ApiClient.Resp resp = client().postWithoutCsrf("/api/auth/magic-link", Map.of("email", uniqueEmail("csrf")));
        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.errorCode()).isEqualTo("FORBIDDEN");
    }

    @Test
    void configuredAdminEmailGetsAdminRole() {
        ApiClient client = client();
        client.post("/api/auth/magic-link", Map.of("email", "Boss@Example.com"));
        client.post(
                "/api/auth/email-code/verify", Map.of("email", "boss@example.com", "code", FakeResend.lastCode(FAKE)));
        assertThat(client.get("/api/me").json().path("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    void sessionIsStoredInPostgres() {
        ApiClient client = client();
        String email = uniqueEmail("persist");
        client.post("/api/auth/magic-link", Map.of("email", email));
        client.post("/api/auth/email-code/verify", Map.of("email", email, "code", FakeResend.lastCode(FAKE)));
        Integer sessions = jdbc.sql("SELECT count(*) FROM spring_session s JOIN spring_session_attributes a "
                        + "ON a.session_primary_id = s.primary_id WHERE a.attribute_name = 'SPRING_SECURITY_CONTEXT'")
                .query(Integer.class)
                .single();
        assertThat(sessions).isPositive();
        assertThat(client.cookie("IJ_SESSION")).isNotBlank();
    }
}
