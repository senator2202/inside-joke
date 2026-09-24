package com.insidejoke.auth;

import java.util.Optional;
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

    public static final String REGISTRATION_ID = "google";

    private final Optional<ClientRegistrationRepository> repository;

    public GoogleClientRegistrationRepository(GoogleProperties google) {
        if (!google.enabled()) {
            this.repository = Optional.empty();
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
        this.repository = Optional.of(new InMemoryClientRegistrationRepository(registration));
    }

    public Optional<ClientRegistrationRepository> repository() {
        return repository;
    }

    public boolean enabled() {
        return repository.isPresent();
    }
}
