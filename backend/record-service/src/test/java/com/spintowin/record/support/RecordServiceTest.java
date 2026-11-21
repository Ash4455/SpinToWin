package com.spintowin.record.support;

import com.spintowin.common.security.KeycloakRealmRoleConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * record-service 測試基底：完整 Spring context（含 Kafka listener）+ 真 MySQL + 真 Kafka + MockMvc + mock JWT。
 * 每個測試用 {@link #newCampaignId()} 取得自己的 Campaign，所以共用 container 的測試彼此不干擾。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({MySqlContainerConfiguration.class, KafkaContainerConfiguration.class})
public abstract class RecordServiceTest {

    private static final AtomicLong CAMPAIGN_IDS = new AtomicLong(1000);

    @Autowired
    protected MockMvcTester mvc;

    /** 扮演 draw-service：送出 draw.completed、讀回 dead-letter topic。 */
    @Autowired
    protected DrawCompletedTopic topic;

    /** 每個測試自己的 Campaign（key = campaignId，因此也各自決定 partition）。 */
    protected static long newCampaignId() {
        return CAMPAIGN_IDS.incrementAndGet();
    }

    protected static String newUserId() {
        return UUID.randomUUID().toString();
    }

    protected static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    /** 已登入、具 USER realm role 的使用者。 */
    protected static JwtRequestPostProcessor user(String userId) {
        return keycloakJwt(userId, "USER");
    }

    /** 已登入、只具 ADMIN realm role 的營運人員。 */
    protected static JwtRequestPostProcessor admin() {
        return keycloakJwt("8f9c7a52-0000-4000-8000-0000000000ad", "ADMIN");
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
