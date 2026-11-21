package com.spintowin.gateway.config;

import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.error.Problems;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * gateway（WebFlux）未帶或帶了無效的 JWT：回 401 Problem Details（code {@code UNAUTHORIZED}），
 * 並帶 RFC 6750 的 {@code WWW-Authenticate: Bearer ...} header。與 servlet 服務的
 * {@code ProblemDetailsAuthenticationEntryPoint} 回應格式相同。
 */
public class ProblemDetailsServerAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final JsonMapper jsonMapper;

    public ProblemDetailsServerAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException authException) {
        return Mono.defer(() -> {
            ProblemDetail problem = Problems.of(ErrorCode.UNAUTHORIZED, "Authentication is required.");
            problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
            byte[] body = jsonMapper.writeValueAsBytes(problem);

            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(ErrorCode.UNAUTHORIZED.status());
            response.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, wwwAuthenticate(authException));
            response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
        });
    }

    private static String wwwAuthenticate(AuthenticationException authException) {
        if (!(authException instanceof OAuth2AuthenticationException oauth2)) {
            return "Bearer";
        }
        OAuth2Error error = oauth2.getError();
        List<String> params = new ArrayList<>();
        params.add("error=\"" + error.getErrorCode() + "\"");
        if (error.getDescription() != null) {
            params.add("error_description=\"" + error.getDescription() + "\"");
        }
        if (error.getUri() != null) {
            params.add("error_uri=\"" + error.getUri() + "\"");
        }
        return "Bearer " + String.join(", ", params);
    }
}
