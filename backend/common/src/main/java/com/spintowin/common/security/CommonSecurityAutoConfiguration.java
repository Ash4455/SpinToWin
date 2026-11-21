package com.spintowin.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

/**
 * servlet 的 OAuth2 Resource Server 服務共用的 security 元件。
 * 各服務的 {@code config.SecurityConfig} 注入這些 bean 組出自己的 filter chain。
 */
@AutoConfiguration(before = OAuth2ResourceServerAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(BearerTokenAuthenticationEntryPoint.class)
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailsAuthenticationEntryPoint problemDetailsAuthenticationEntryPoint(JsonMapper jsonMapper) {
        return new ProblemDetailsAuthenticationEntryPoint(jsonMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemDetailsAccessDeniedHandler problemDetailsAccessDeniedHandler(JsonMapper jsonMapper) {
        return new ProblemDetailsAccessDeniedHandler(jsonMapper);
    }

    /** 使用者 id 取自 token 的 subject；角色取自 Keycloak realm roles。 */
    @Bean
    @ConditionalOnMissingBean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRoleConverter());
        return converter;
    }
}
