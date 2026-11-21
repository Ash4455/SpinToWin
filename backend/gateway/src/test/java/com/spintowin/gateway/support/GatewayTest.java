package com.spintowin.gateway.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;

/**
 * gateway 的 HTTP 邊界測試基底。各服務與 Keycloak JWKS 由 {@link FakeUpstream} 扮演；
 * Eureka 關閉，改用 simple discovery client 解析服務名稱；限流用真 Redis（Testcontainers）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "eureka.client.enabled=false")
@AutoConfigureWebTestClient
@Import(RedisContainerConfiguration.class)
public abstract class GatewayTest {

    protected static final TestTokens TOKENS = TestTokens.generate();
    /** 扮演 campaign-service，同時提供 gateway 用的 JWKS（{@code /certs}）。 */
    protected static final FakeUpstream UPSTREAM = FakeUpstream.start(TOKENS.publicKey());
    /** 扮演 draw-service（它也會回應 /certs，但 gateway 的 JWKS 只指向 {@link #UPSTREAM}）。 */
    protected static final FakeUpstream DRAW_UPSTREAM = FakeUpstream.start(TOKENS.publicKey());
    /** 扮演 record-service。 */
    protected static final FakeUpstream RECORD_UPSTREAM = FakeUpstream.start(TOKENS.publicKey());

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.campaign-service[0].uri", UPSTREAM::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.draw-service[0].uri", DRAW_UPSTREAM::baseUrl);
        registry.add("spring.cloud.discovery.client.simple.instances.record-service[0].uri", RECORD_UPSTREAM::baseUrl);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> UPSTREAM.baseUrl() + FakeUpstream.JWKS_PATH);
    }

    @Autowired
    protected WebTestClient client;

    @BeforeEach
    void resetUpstreams() {
        UPSTREAM.reset();
        DRAW_UPSTREAM.reset();
        RECORD_UPSTREAM.reset();
    }

    /** 每個測試用新的使用者，限流的 Redis key 不會互相影響。 */
    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    protected static String userToken(String userId) {
        return TOKENS.accessToken(TestTokens.PUBLIC_ISSUER, userId, "USER");
    }
}
