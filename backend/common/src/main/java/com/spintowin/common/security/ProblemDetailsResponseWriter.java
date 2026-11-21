package com.spintowin.common.security;

import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.error.Problems;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/** 在 Spring MVC 之外（security filter chain 內）直接寫出 Problem Details 回應。 */
final class ProblemDetailsResponseWriter {

    private final JsonMapper jsonMapper;

    ProblemDetailsResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        ProblemDetail problem = Problems.of(code, detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), problem);
    }
}
