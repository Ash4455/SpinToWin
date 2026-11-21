package com.spintowin.common.security;

import com.spintowin.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

/** 已登入但角色不足：回 403 Problem Details（code {@code FORBIDDEN}）。 */
public class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

    private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
    private final ProblemDetailsResponseWriter writer;

    public ProblemDetailsAccessDeniedHandler(JsonMapper jsonMapper) {
        this.writer = new ProblemDetailsResponseWriter(jsonMapper);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        bearer.handle(request, response, accessDeniedException);
        writer.write(request, response, ErrorCode.FORBIDDEN, "You do not have permission to access this resource.");
    }
}
