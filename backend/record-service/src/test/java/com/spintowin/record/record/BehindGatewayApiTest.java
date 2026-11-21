package com.spintowin.record.record;

import com.spintowin.record.support.RecordServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 gateway 後面運作所需的契約：OpenAPI 文件不需登入即可讀取（Swagger UI 匯集用）並說明路由、參數與錯誤碼；
 * gateway 帶來的 {@code X-Forwarded-Prefix} 讓 Problem Details 的 instance 是使用者呼叫的 /api/v1 路徑。
 */
class BehindGatewayApiTest extends RecordServiceTest {

    private static final String MY_DRAWS = "$.paths['/campaigns/{campaignId}/draws/me'].get";

    @Test
    void apiDocsArePublicAndDescribeTheEndpoints() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"info": {"title": "record-service"}, "servers": [{"url": "/api/v1"}]}
                """);
        assertThat(response).bodyJson().extractingPath("$.paths").asMap()
                .containsOnlyKeys("/campaigns/{campaignId}/draws/me", "/admin/campaigns/{campaignId}/stats");
        assertThat(response).bodyJson().extractingPath("$.paths.*.*.summary").asArray()
                .hasSize(2).allSatisfy(summary -> assertThat(summary).asString().isNotBlank());
        assertThat(response).bodyJson().extractingPath(MY_DRAWS + ".parameters[*].name").asArray()
                .containsExactly("campaignId", "page", "size");
        assertThat(response).bodyJson().extractingPath(MY_DRAWS + ".parameters[*].description").asArray()
                .hasSize(3).allSatisfy(description -> assertThat(description).asString().isNotBlank());
        assertThat(response).bodyJson()
                .extractingPath(MY_DRAWS + ".responses['400'].content['application/problem+json'].examples").asMap()
                .containsKeys("VALIDATION_FAILED");
    }

    @Test
    void problemInstanceIncludesTheGatewayPrefix() {
        var response = mvc.get().uri("/admin/campaigns/1/stats").with(user(newUserId()))
                .header("X-Forwarded-Prefix", "/api/v1")
                .exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 403, "code": "FORBIDDEN", "instance": "/api/v1/admin/campaigns/1/stats"}
                """);
    }
}
