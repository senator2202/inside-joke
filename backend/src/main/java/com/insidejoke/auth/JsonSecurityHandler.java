package com.insidejoke.auth;

import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.dto.ErrorDto;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** 401 and 403 as JSON errors instead of redirects to a login page. */
@Component
public class JsonSecurityHandler {

    private final JsonMapper json;

    public JsonSecurityHandler(JsonMapper json) {
        this.json = json;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) ->
                write(response, ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    public AccessDeniedHandler accessDenied() {
        return (request, response, ex) -> write(
                response,
                ErrorCode.FORBIDDEN,
                ex instanceof CsrfException
                        ? "Security token missing or expired. Reload the page."
                        : ErrorCode.FORBIDDEN.defaultMessage());
    }

    private void write(HttpServletResponse response, ErrorCode code, String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(json.writeValueAsString(ErrorDto.of(code, message, Map.of())));
    }
}
