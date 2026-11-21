package com.spintowin.draw.support;

import com.spintowin.common.security.KeycloakRealmRoleConverter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * draw-service 測試基底：完整 Spring context + 真 Redis 與 Kafka（Testcontainers），沒有 MySQL。
 * outbox relay 在背景執行，每次成功的抽獎都會被轉送到 Kafka（以 {@link #drawCompletedTopic} 讀回）。
 * <ul>
 *   <li>Seam 1：直接呼叫 {@code DrawService}，以 {@link #random} 安排每一抽的 ppm 落點。</li>
 *   <li>HTTP：{@link #mvc} + mock JWT（{@link #user(String)}）。</li>
 * </ul>
 * Campaign 的 live state 以 {@link #campaigns} 寫入 Redis；每個測試用新的 campaignId 與 userId，互不干擾。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({RedisContainerConfiguration.class, KafkaContainerConfiguration.class, DrawTest.FixedRandomConfiguration.class})
public abstract class DrawTest {

    @Autowired
    protected MockMvcTester mvc;

    @Autowired
    protected StringRedisTemplate redis;

    /** 取代正式的 SecureRandom：測試以 {@code random.willReturn(..)} 決定每一抽的落點。 */
    @Autowired
    protected SequenceRandomPpmSource random;

    /** Kafka topic draw.completed 的讀取端。 */
    @Autowired
    protected DrawCompletedTopic drawCompletedTopic;

    protected LiveCampaignFixture campaigns;

    @BeforeEach
    void resetFixtures() {
        random.reset();
        campaigns = new LiveCampaignFixture(redis);
    }

    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    protected static String newIdempotencyKey() {
        return UUID.randomUUID().toString();
    }

    /** 已登入、具 USER realm role 的使用者（subject = userId）。 */
    protected static JwtRequestPostProcessor user(String userId) {
        return keycloakJwt(userId, "USER");
    }

    /**
     * 模擬 Keycloak 簽發的 access token：subject 為使用者 id，角色放在 {@code realm_access.roles}，
     * 並以正式環境相同的 {@link KeycloakRealmRoleConverter} 轉成 authorities。
     */
    protected static JwtRequestPostProcessor keycloakJwt(String subject, String... realmRoles) {
        return jwt()
                .jwt(j -> j.subject(subject)
                        .claim("realm_access", Map.of("roles", List.of(realmRoles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedRandomConfiguration {

        @Bean
        @Primary
        SequenceRandomPpmSource sequenceRandomPpmSource() {
            return new SequenceRandomPpmSource();
        }
    }
}
