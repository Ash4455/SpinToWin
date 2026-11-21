package com.spintowin.gateway.config;

import java.time.Duration;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.JwkSetUriReactiveJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * gateway 向 Keycloak 取 JWKS 用的 HTTP client。
 *
 * <p>Reactor Netty 依 DNS 回應的 TTL 快取主機位址（Docker 內建 DNS 給 600 秒）；
 * Keycloak 容器重建、換了 IP 後，gateway 會一直連舊位址而回 500。這裡把快取上限壓短，
 * 讓 gateway 在 Keycloak 換位址後很快就能重新取得公鑰。
 */
@Configuration(proxyBeanMethods = false)
public class JwkSetClientConfig {

    static final Duration DNS_CACHE_MAX_TTL = Duration.ofSeconds(10);

    @Bean
    JwkSetUriReactiveJwtDecoderBuilderCustomizer jwkSetWebClientCustomizer() {
        HttpClient httpClient = HttpClient.create()
                .resolver(spec -> spec.cacheMaxTimeToLive(DNS_CACHE_MAX_TTL));
        WebClient webClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        return builder -> builder.webClient(webClient);
    }
}
