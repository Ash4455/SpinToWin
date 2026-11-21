package com.spintowin.draw.draw;

import com.spintowin.draw.support.DrawTest;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 gateway 後面運作所需的契約：OpenAPI 文件不需登入即可讀取（Swagger UI 匯集用）並說明路由、參數與錯誤碼；
 * gateway 帶來的 {@code X-Forwarded-Prefix} 讓 Problem Details 的 instance 是使用者呼叫的 /api/v1 路徑。
 */
class BehindGatewayApiTest extends DrawTest {

    private static final String DRAWS = "$.paths['/campaigns/{campaignId}/draws'].post";

    @Test
    void apiDocsArePublicAndDescribeTheDrawEndpoint() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"info": {"title": "draw-service"}, "servers": [{"url": "/api/v1"}]}
                """);
        assertThat(response).bodyJson().extractingPath(DRAWS + ".summary").asString().isNotBlank();
        assertThat(response).bodyJson().extractingPath(DRAWS + ".parameters[?(@.name == 'Idempotency-Key')]")
                .asArray().singleElement().asInstanceOf(InstanceOfAssertFactories.MAP)
                .containsEntry("in", "header").containsEntry("required", true).containsKey("description");
        assertThat(response).bodyJson().extractingPath(DRAWS + ".responses").asMap()
                .containsKeys("200", "400", "401", "403", "409", "429");
        assertThat(response).bodyJson()
                .extractingPath(DRAWS + ".responses['409'].content['application/problem+json'].examples").asMap()
                .containsKeys("QUOTA_EXCEEDED", "CAMPAIGN_NOT_ACTIVE");
        assertThat(response).bodyJson()
                .extractingPath(DRAWS + ".responses['400'].content['application/problem+json'].examples").asMap()
                .containsKeys("IDEMPOTENCY_KEY_REQUIRED", "VALIDATION_FAILED");
        assertThat(response).bodyJson()
                .extractingPath(DRAWS + ".responses['429'].content['application/problem+json'].examples").asMap()
                .containsKeys("RATE_LIMITED");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/campaigns/{campaignId}/me'].get.responses['409'].description").asString()
                .contains("CAMPAIGN_NOT_ACTIVE");
    }

    @Test
    void problemInstanceIncludesTheGatewayPrefix() {
        var response = mvc.post().uri("/campaigns/1/draws")
                .header("X-Forwarded-Prefix", "/api/v1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count": 1}
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 401, "code": "UNAUTHORIZED", "instance": "/api/v1/campaigns/1/draws"}
                """);
    }
}
