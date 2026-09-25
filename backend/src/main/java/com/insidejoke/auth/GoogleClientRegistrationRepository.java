package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.stereotype.Component;

/** Google OIDC client registration; empty when GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET are not set. */
@Component
public class GoogleClientRegistrationRepository {

    private static final Logger log = LoggerFactory.getLogger(GoogleClientRegistrationRepository.class);

    public static final String REGISTRATION_ID = "google";

    private final @Nullable ClientRegistrationRepository repository;

    public GoogleClientRegistrationRepository(GoogleProperties google, AppProperties app) {
        log.info(describe(google, app.publicUrl()));
        if (!google.enabled()) {
            this.repository = null;
            return;
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(google.clientId())
                .clientSecret(google.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "email", "profile")
                .authorizationUri(google.authorizationUri())
                .tokenUri(google.tokenUri())
                .jwkSetUri(google.jwkSetUri())
                .userInfoUri(google.userInfoUri())
                .issuerUri(google.issuerUri())
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .clientName("Google")
                .build();
        this.repository = new InMemoryClientRegistrationRepository(registration);
    }

    public Optional<ClientRegistrationRepository> repository() {
        return Optional.ofNullable(repository);
    }

    public boolean enabled() {
        return repository != null;
    }

    /** One startup line: whether the sign-in page offers Google, and what to fix or register. */
    static String describe(GoogleProperties google, String publicUrl) {
        if (google.enabled()) {
            return "Google sign-in is on. Redirect URI to register in Google Cloud: " + publicUrl
                    + "/login/oauth2/code/google";
        }
        boolean noId = google.clientId() == null || google.clientId().isBlank();
        boolean noSecret =
                google.clientSecret() == null || google.clientSecret().isBlank();
        String missing = noId && noSecret
                ? "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET are"
                : noId ? "GOOGLE_CLIENT_ID is" : "GOOGLE_CLIENT_SECRET is";
        return "Google sign-in is off: " + missing + " not set (environment or .env in the working directory), "
                + "so the sign-in page shows only the email code";
    }
}
