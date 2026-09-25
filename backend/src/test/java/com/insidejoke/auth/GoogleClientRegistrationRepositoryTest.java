package com.insidejoke.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GoogleClientRegistrationRepositoryTest {

    private static GoogleProperties google(String id, String secret) {
        return new GoogleProperties(id, secret, null, null, null, null, null);
    }

    @Test
    void whenOnItNamesTheRedirectUriToRegister() {
        assertThat(GoogleClientRegistrationRepository.describe(google("id", "secret"), "http://localhost:5173"))
                .isEqualTo("Google sign-in is on. Redirect URI to register in Google Cloud: "
                        + "http://localhost:5173/login/oauth2/code/google");
    }

    @Test
    void whenOffItNamesWhatIsMissing() {
        assertThat(GoogleClientRegistrationRepository.describe(google("", " "), "x"))
                .startsWith("Google sign-in is off: GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are not set");
        assertThat(GoogleClientRegistrationRepository.describe(google("id", null), "x"))
                .startsWith("Google sign-in is off: GOOGLE_CLIENT_SECRET is not set");
        assertThat(GoogleClientRegistrationRepository.describe(google(null, "secret"), "x"))
                .startsWith("Google sign-in is off: GOOGLE_CLIENT_ID is not set");
    }
}
