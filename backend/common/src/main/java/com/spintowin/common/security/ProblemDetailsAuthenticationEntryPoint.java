package com.spintowin.common.security;

import com.spintowin.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/**
 * 未帶或帶了無效的 JWT：回 401 Problem Details（code {@code UNAUTHORIZED}），
 * 並保留 RFC 6750 的 {@code WWW-Authenticate: Bearer ...} header。
 */
public class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
    private final ProblemDetailsResponseWriter writer;

    public ProblemDetailsAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailsResponseWriter(jsonMapper);
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        bearer.commence(request, response, authException);
        writer.write(request, response, ErrorCode.UNAUTHORIZED, "Authentication is required.");
    }
}
