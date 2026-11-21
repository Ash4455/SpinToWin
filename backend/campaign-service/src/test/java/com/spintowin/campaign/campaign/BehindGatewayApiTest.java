package com.spintowin.campaign.campaign;

import com.spintowin.campaign.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 在 gateway 後面運作所需的契約：OpenAPI 文件不需登入即可讀取（Swagger UI 匯集用）並說明路由、參數與錯誤碼；
 * gateway 帶來的 {@code X-Forwarded-*} 讓 Problem Details 的 instance 與 Location 是使用者呼叫的網址。
 */
class BehindGatewayApiTest extends ApiTest {

    @Test
    void apiDocsArePublicAndEveryOperationHasASummary() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"info": {"title": "campaign-service"}, "servers": [{"url": "/api/v1"}]}
                """);
        assertThat(response).bodyJson().extractingPath("$.paths").asMap().containsOnlyKeys(
                "/campaigns/{id}",
                "/admin/campaigns", "/admin/campaigns/{id}", "/admin/campaigns/{id}/activate",
                "/admin/campaigns/{id}/end", "/admin/campaigns/{id}/redis/rebuild",
                "/admin/campaigns/{campaignId}/prizes", "/admin/campaigns/{campaignId}/prizes/{prizeId}",
                "/admin/campaigns/{campaignId}/prizes/{prizeId}/stock");
        assertThat(response).bodyJson().extractingPath("$.paths.*.*.summary").asArray()
                .hasSize(12).allSatisfy(summary -> assertThat(summary).asString().isNotBlank());
    }

    @Test
    void apiDocsListTheErrorCodesOfEachOperation() {
        var response = mvc.get().uri("/v3/api-docs").exchange();

        String activate = "$.paths['/admin/campaigns/{id}/activate'].post.responses";
        assertThat(response).bodyJson()
                .extractingPath(activate + "['409'].content['application/problem+json'].examples").asMap()
                .containsKeys("INVALID_STATUS_TRANSITION", "CAMPAIGN_HAS_NO_PRIZE", "PROBABILITY_SUM_EXCEEDED");
        assertThat(response).bodyJson()
                .extractingPath(activate + "['404'].content['application/problem+json'].examples").asMap()
                .containsKeys("CAMPAIGN_NOT_FOUND");

        String stock = "$.paths['/admin/campaigns/{campaignId}/prizes/{prizeId}/stock'].patch.responses";
        assertThat(response).bodyJson()
                .extractingPath(stock + "['409'].content['application/problem+json'].examples").asMap()
                .containsKeys("STOCK_NEGATIVE", "CAMPAIGN_NOT_EDITABLE");
        assertThat(response).bodyJson()
                .extractingPath(stock + "['404'].content['application/problem+json'].examples").asMap()
                .containsKeys("CAMPAIGN_NOT_FOUND", "PRIZE_NOT_FOUND");
        assertThat(response).bodyJson()
                .extractingPath("$.paths['/campaigns/{id}'].get.parameters[0].description").asString().isNotBlank();
    }

    @Test
    void problemInstanceIncludesTheGatewayPrefix() {
        var response = mvc.get().uri("/campaigns/999999").with(user())
                .header("X-Forwarded-Prefix", "/api/v1")
                .exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 404, "code": "CAMPAIGN_NOT_FOUND", "instance": "/api/v1/campaigns/999999"}
                """);
    }

    @Test
    void locationOfACreatedCampaignIsTheGatewayUrl() {
        var response = mvc.post().uri("/admin/campaigns").with(admin())
                .header("X-Forwarded-Host", "localhost:8000")
                .header("X-Forwarded-Proto", "http")
                .header("X-Forwarded-Prefix", "/api/v1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name": "Gateway URL", "startAt": "2030-01-01T00:00:00Z",
                         "endAt": "2030-02-01T00:00:00Z", "drawQuota": 3}
                        """)
                .exchange();

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).headers().hasHeaderSatisfying(HttpHeaders.LOCATION, values -> assertThat(values)
                .singleElement().asString().matches("http://localhost:8000/api/v1/admin/campaigns/\\d+"));
    }
}
