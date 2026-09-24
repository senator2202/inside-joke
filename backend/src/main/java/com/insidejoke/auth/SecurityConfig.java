package com.insidejoke.auth;

import com.insidejoke.common.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), new HttpSessionSecurityContextRepository());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AppProperties props,
            SecurityContextRepository contextRepository,
            JsonSecurityHandler handlers,
            GoogleClientRegistrationRepository google,
            ObjectProvider<GoogleLoginSuccessHandler> googleSuccess)
            throws Exception {
        http.csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/webhooks/**", "/api/events"))
                .securityContext(sc -> sc.securityContextRepository(contextRepository))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/rooms")
                        .authenticated()
                        .requestMatchers("/api/rooms/*/owner-token")
                        .authenticated()
                        .requestMatchers("/api/me", "/api/me/**", "/api/games/**")
                        .authenticated()
                        .requestMatchers("/api/billing/checkout", "/api/billing/passes")
                        .authenticated()
                        .anyRequest()
                        .permitAll())
                .exceptionHandling(e ->
                        e.authenticationEntryPoint(handlers.entryPoint()).accessDeniedHandler(handlers.accessDenied()))
                .headers(h -> h.contentSecurityPolicy(csp -> csp.policyDirectives(props.contentSecurityPolicy()))
                        .referrerPolicy(r ->
                                r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        if (google.repository().isPresent()) {
            ClientRegistrationRepository registrations = google.repository().get();
            http.oauth2Login(o -> o.clientRegistrationRepository(registrations)
                    .successHandler(googleSuccess.getObject())
                    .failureHandler((request, response, ex) ->
                            response.sendRedirect(props.publicUrl() + "/login?error=google")));
        }
        return http.build();
    }
}
