package com.insidejoke.auth;

import com.insidejoke.auth.dto.AuthConfigDto;
import com.insidejoke.auth.dto.CodeRequestDto;
import com.insidejoke.auth.dto.CodeSentDto;
import com.insidejoke.auth.dto.CsrfDto;
import com.insidejoke.auth.dto.EmailRequestDto;
import com.insidejoke.auth.dto.LinkRequestDto;
import com.insidejoke.auth.dto.SignedInDto;
import com.insidejoke.common.Language;
import com.insidejoke.web.ClientIpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final EmailLoginService emailLogin;
    private final SessionLoginService sessionLogin;
    private final GoogleClientRegistrationRepository google;
    private final LoginEventService events;

    public AuthController(
            EmailLoginService emailLogin,
            SessionLoginService sessionLogin,
            GoogleClientRegistrationRepository google,
            LoginEventService events) {
        this.emailLogin = emailLogin;
        this.sessionLogin = sessionLogin;
        this.google = google;
        this.events = events;
    }

    @GetMapping("/config")
    public AuthConfigDto config() {
        return new AuthConfigDto(google.enabled());
    }

    /** Touching the deferred token makes Spring Security write the XSRF-TOKEN cookie for the SPA. */
    @GetMapping("/csrf")
    public CsrfDto csrf(CsrfToken token) {
        return new CsrfDto(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/magic-link")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSentDto sendCode(@RequestBody @Valid EmailRequestDto body, HttpServletRequest request) {
        emailLogin.requestCode(
                body.email(),
                ClientIpUtils.of(request),
                Language.fromCode(body.language()).orElse(Language.EN));
        return new CodeSentDto(true, 60);
    }

    @PostMapping("/email-code/verify")
    public SignedInDto verifyCode(
            @RequestBody @Valid CodeRequestDto body, HttpServletRequest request, HttpServletResponse response) {
        UserEntity user = emailLogin.verifyCode(body.email(), body.code().replace(" ", ""), ClientIpUtils.of(request));
        sessionLogin.establish(user, request, response);
        events.loggedIn(user, "email_code");
        return new SignedInDto(true);
    }

    @PostMapping("/magic-link/verify")
    public SignedInDto verifyLink(
            @RequestBody @Valid LinkRequestDto body, HttpServletRequest request, HttpServletResponse response) {
        UserEntity user = emailLogin.verifyLink(body.token());
        sessionLogin.establish(user, request, response);
        events.loggedIn(user, "magic_link");
        return new SignedInDto(true);
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        new SecurityContextLogoutHandler()
                .logout(request, response, SecurityContextHolder.getContext().getAuthentication());
    }
}
