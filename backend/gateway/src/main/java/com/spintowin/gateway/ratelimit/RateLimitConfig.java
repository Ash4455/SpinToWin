package com.spintowin.gateway.ratelimit;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * 抽獎 API 的每使用者限流：Redis token bucket，key 是 JWT 的 subject（與各服務認定的使用者 id 相同）。
 * 速率在 application.yml 的 draw-service-draws 路由上設定。
 */
@Configuration(proxyBeanMethods = false)
public class RateLimitConfig {

    /**
     * 未登入的請求在 security 就被 401 擋下，到這裡一定有 JWT；萬一沒有，key 為空，
     * 內建行為是拒絕（deny-empty-key）。
     */
    @Bean
    KeyResolver jwtSubjectKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken().getSubject());
    }

    @Bean
    ProblemDetailsRequestRateLimiterGatewayFilterFactory problemDetailsRequestRateLimiterGatewayFilterFactory(
            RedisRateLimiter redisRateLimiter, KeyResolver jwtSubjectKeyResolver, JsonMapper jsonMapper) {
        return new ProblemDetailsRequestRateLimiterGatewayFilterFactory(redisRateLimiter, jwtSubjectKeyResolver,
                jsonMapper);
    }
}
