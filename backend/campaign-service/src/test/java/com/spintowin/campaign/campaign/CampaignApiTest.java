package com.spintowin.campaign.campaign;

import com.jayway.jsonpath.JsonPath;
import com.spintowin.campaign.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code GET /campaigns/{id}}：USER 讀取 Campaign 資訊、Prize 清單與 Win Probability。 */
class CampaignApiTest extends ApiTest {

    private static final long SEED_CAMPAIGN_ID = 1L;

    @Test
    void userReadsSeedCampaignWithPrizesAndNoWinProbability() {
        var response = mvc.get().uri("/campaigns/{id}", SEED_CAMPAIGN_ID).with(user()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "id": 1,
                          "name": "週年慶轉盤抽獎",
                          "startAt": "2026-01-01T00:00:00Z",
                          "endAt": "2030-12-31T23:59:59Z",
                          "drawQuota": 10,
                          "status": "ACTIVE",
                          "prizes": [
                            { "id": 1, "name": "藍牙耳機",      "winProbability": 1.0 },
                            { "id": 2, "name": "100 元購物金",  "winProbability": 9.5 },
                            { "id": 3, "name": "免運券",        "winProbability": 30.0 }
                          ],
                          "noWinProbability": 59.5
                        }
                        """);
        // Prize 順序決定 ppm 區間的累加順序，必須穩定
        assertThat(response).bodyJson()
                .extractingPath("$.prizes[*].name")
                .asArray()
                .containsExactly("藍牙耳機", "100 元購物金", "免運券");
    }

    @Test
    void anonymousRequestIsUnauthorizedProblem() {
        var response = mvc.get().uri("/campaigns/{id}", SEED_CAMPAIGN_ID).exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 401,
                  "title": "Unauthorized",
                  "code": "UNAUTHORIZED",
                  "instance": "/campaigns/1"
                }
                """);
    }

    @Test
    void tokenWithoutUserRoleIsForbiddenProblem() {
        var response = mvc.get().uri("/campaigns/{id}", SEED_CAMPAIGN_ID).with(admin()).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 403,
                  "title": "Forbidden",
                  "code": "FORBIDDEN",
                  "instance": "/campaigns/1"
                }
                """);
    }

    @Test
    void nonNumericCampaignIdIsValidationProblemWithFieldError() {
        var response = mvc.get().uri("/campaigns/{id}", "abc").with(user()).exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 400,
                  "code": "VALIDATION_FAILED",
                  "instance": "/campaigns/abc",
                  "errors": [ { "field": "id" } ]
                }
                """);
    }

    @Test
    void draftCampaignIsNotFoundForUsersButStillVisibleToAdmins() throws Exception {
        var created = mvc.post().uri("/admin/campaigns").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "草稿活動", "startAt": "2027-01-20T00:00:00Z",
                          "endAt": "2027-02-10T23:59:59Z", "drawQuota": 3 }
                        """)
                .exchange();
        assertThat(created).hasStatus(HttpStatus.CREATED);
        Number id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        var response = mvc.get().uri("/campaigns/{id}", id).with(user()).exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 404, "code": "CAMPAIGN_NOT_FOUND", "instance": "/campaigns/%d" }
                """.formatted(id.longValue()));
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange()).hasStatusOk();
    }

    @Test
    void unknownCampaignIsNotFoundProblem() {
        var response = mvc.get().uri("/campaigns/{id}", 999_999).with(user()).exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 404,
                  "title": "Not Found",
                  "code": "CAMPAIGN_NOT_FOUND",
                  "instance": "/campaigns/999999"
                }
                """);
    }
}
