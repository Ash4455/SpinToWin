package com.spintowin.campaign.support;

import com.spintowin.common.security.KeycloakRealmRoleConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * HTTP API 測試基底（Seam 2）：完整 Spring context + MockMvc + mock JWT + 真 MySQL + 真 Redis + 真 Kafka。
 * 只透過 HTTP 觀察行為，不直接查資料庫；唯一例外是 Redis 的 live state（{@link #redis}），
 * 因為它是 campaign-service 交給 draw-service 的對外合約（見 {@code CampaignRedisKeys}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MySqlContainerConfiguration.class, RedisContainerConfiguration.class, KafkaContainerConfiguration.class})
public abstract class ApiTest {

    protected static final String USER_ID = "8f9c7a52-0000-4000-8000-000000000001";
    protected static final String ADMIN_ID = "8f9c7a52-0000-4000-8000-0000000000ad";

    @Autowired
    protected MockMvcTester mvc;

    /** 以 draw-service 的角度讀寫 Redis live state。 */
    @Autowired
    protected StringRedisTemplate redis;

    /** 已登入、具 USER realm role 的使用者。 */
    protected static JwtRequestPostProcessor user() {
        return keycloakJwt(USER_ID, "USER");
    }

    /** 已登入、只具 ADMIN realm role 的營運人員。 */
    protected static JwtRequestPostProcessor admin() {
        return keycloakJwt(ADMIN_ID, "ADMIN");
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
}
