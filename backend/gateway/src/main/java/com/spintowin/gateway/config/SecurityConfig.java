package com.spintowin.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * gateway 是 OAuth2 Resource Server：/api/v1 下的請求都必須帶有效的 Keycloak JWT，
 * 驗證通過後原樣轉送 Authorization header，角色檢查交給各服務。錯誤一律回 Problem Details。
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    @Bean
    ProblemDetailsServerAuthenticationEntryPoint problemDetailsServerAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return new ProblemDetailsServerAuthenticationEntryPoint(jsonMapper);
    }

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http,
                                                  ProblemDetailsServerAuthenticationEntryPoint authenticationEntryPoint) {
        http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(a -> a
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Swagger UI 與各服務的 OpenAPI 文件（登入前就要讀取；試打 API 時才帶 token）
                        .pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/*/v3/api-docs").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint));
        return http.build();
    }
}
