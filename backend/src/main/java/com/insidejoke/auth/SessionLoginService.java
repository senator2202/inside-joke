package com.insidejoke.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

/** Puts a host into the server-side session, the same way for Google and email sign-in. */
@Service
public class SessionLoginService {

    private final SecurityContextRepository contextRepository;

    public SessionLoginService(SecurityContextRepository contextRepository) {
        this.contextRepository = contextRepository;
    }

    public void establish(UserEntity user, HttpServletRequest request, HttpServletResponse response) {
        AppPrincipal principal = user.toPrincipal();
        // Admin access is decided per request by AdminAccessService, never by an authority kept in the session.
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_HOST"));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        SecurityContextHolder.setContext(context);
        request.getSession(true);
        request.changeSessionId();
        contextRepository.saveContext(context, request, response);
    }
}
