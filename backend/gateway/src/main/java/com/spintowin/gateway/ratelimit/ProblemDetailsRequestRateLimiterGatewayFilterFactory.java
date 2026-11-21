package com.spintowin.gateway.ratelimit;

import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.error.Problems;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.RequestRateLimiterGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

/**
 * 與內建 {@code RequestRateLimiter} 相同（同樣的參數、Redis token bucket、{@code X-RateLimit-*} header），
 * 但被擋下時回 429 Problem Details（code {@code RATE_LIMITED}）與 {@code Retry-After}，而不是空白 body。
 * 路由設定中以 {@code ProblemDetailsRequestRateLimiter} 使用，且要放在 {@code StripPrefix} 之前，
 * {@code instance} 才會是使用者呼叫的 /api/v1 路徑。
 */
public class ProblemDetailsRequestRateLimiterGatewayFilterFactory extends RequestRateLimiterGatewayFilterFactory {

    /** 每秒回補 token，最快一秒後就能再試。 */
    static final String RETRY_AFTER_SECONDS = "1";

    private final JsonMapper jsonMapper;

    public ProblemDetailsRequestRateLimiterGatewayFilterFactory(RateLimiter<?> rateLimiter, KeyResolver keyResolver,
                                                                JsonMapper jsonMapper) {
        super(rateLimiter, keyResolver);
        this.jsonMapper = jsonMapper;
    }

    @Override
    public GatewayFilter apply(Config config) {
        GatewayFilter limiter = super.apply(config);
        return (exchange, chain) -> limiter.filter(
                exchange.mutate().response(new RateLimitedProblemResponse(exchange)).build(), chain);
    }

    /** 內建 filter 擋下請求時只設定 status 再 {@code setComplete()}；在那一刻改寫成 Problem Details。 */
    private final class RateLimitedProblemResponse extends ServerHttpResponseDecorator {

        private final ServerWebExchange exchange;

        RateLimitedProblemResponse(ServerWebExchange exchange) {
            super(exchange.getResponse());
            this.exchange = exchange;
        }

        @Override
        public Mono<Void> setComplete() {
            if (!HttpStatus.TOO_MANY_REQUESTS.equals(getStatusCode())) {
                return super.setComplete();
            }
            return Mono.defer(() -> {
                ProblemDetail problem = Problems.of(ErrorCode.RATE_LIMITED,
                        "Too many draw requests. Please slow down and try again shortly.");
                problem.setInstance(URI.create(exchange.getRequest().getPath().value()));
                byte[] body = jsonMapper.writeValueAsBytes(problem);

                ServerHttpResponse response = getDelegate();
                response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
                response.getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
                return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
            });
        }
    }
}
