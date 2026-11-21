package com.spintowin.campaign.campaign;

import com.jayway.jsonpath.JsonPath;
import com.spintowin.campaign.support.ApiTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code /admin/campaigns}：ADMIN 建立、修改、列出 Campaign，並上線或結束。 */
class AdminCampaignApiTest extends ApiTest {

    private static final long SEED_CAMPAIGN_ID = 1L;

    private static final String SPRING_SALE = """
            {
              "name": "春節轉盤",
              "startAt": "2027-01-20T00:00:00Z",
              "endAt": "2027-02-10T23:59:59Z",
              "drawQuota": 3
            }
            """;

    @Test
    void adminCreatesCampaignAsDraft() {
        var response = createCampaign(SPRING_SALE);

        assertThat(response).hasStatus(HttpStatus.CREATED);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "name": "春節轉盤",
                  "startAt": "2027-01-20T00:00:00Z",
                  "endAt": "2027-02-10T23:59:59Z",
                  "drawQuota": 3,
                  "status": "DRAFT"
                }
                """);
        long id = idOf(response);
        assertThat(response).containsHeader(HttpHeaders.LOCATION);
        assertThat(response.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("/admin/campaigns/" + id);
    }

    @Test
    void adminReadsCreatedCampaign() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "id": %d,
                  "name": "春節轉盤",
                  "startAt": "2027-01-20T00:00:00Z",
                  "endAt": "2027-02-10T23:59:59Z",
                  "drawQuota": 3,
                  "status": "DRAFT"
                }
                """.formatted(id));
    }

    @Test
    void adminReadingUnknownCampaignIsNotFoundProblem() {
        var response = mvc.get().uri("/admin/campaigns/{id}", 999_999).with(admin()).exchange();

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 404,
                  "code": "CAMPAIGN_NOT_FOUND",
                  "instance": "/admin/campaigns/999999"
                }
                """);
    }

    @Test
    void adminListsAllCampaignsInIdOrder() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = mvc.get().uri("/admin/campaigns").with(admin()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$[0]").asMap().containsAllEntriesOf(Map.of(
                "id", 1, "name", "週年慶轉盤抽獎",
                "startAt", "2026-01-01T00:00:00Z", "endAt", "2030-12-31T23:59:59Z",
                "drawQuota", 10, "status", "ACTIVE", "noWinProbability", 59.5));
        assertThat(response).bodyJson().extractingPath("$[0].prizes[*].id").asArray().containsExactly(1, 2, 3);
        assertThat(response).bodyJson().extractingPath("$[*].id").asArray()
                .anySatisfy(listed -> assertThat(((Number) listed).longValue()).isEqualTo(id));
        assertThat(response).bodyJson().extractingPath("$[*].id").asArray()
                .isSortedAccordingTo((a, b) -> Long.compare(((Number) a).longValue(), ((Number) b).longValue()));
    }

    @Test
    void adminEditsDraftCampaign() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = updateCampaign(id, """
                {
                  "name": "春節轉盤（延長）",
                  "startAt": "2027-01-25T00:00:00Z",
                  "endAt": "2027-02-20T00:00:00Z",
                  "drawQuota": 5
                }
                """);

        assertThat(response).hasStatusOk();
        String expected = """
                {
                  "id": %d,
                  "name": "春節轉盤（延長）",
                  "startAt": "2027-01-25T00:00:00Z",
                  "endAt": "2027-02-20T00:00:00Z",
                  "drawQuota": 5,
                  "status": "DRAFT"
                }
                """.formatted(id);
        assertThat(response).bodyJson().isLenientlyEqualTo(expected);
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange())
                .bodyJson().isLenientlyEqualTo(expected);
    }

    @Test
    void editingActiveCampaignIsConflictProblem() {
        var response = updateCampaign(SEED_CAMPAIGN_ID, SPRING_SALE);

        assertThat(response).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 409,
                  "code": "CAMPAIGN_NOT_EDITABLE",
                  "instance": "/admin/campaigns/1"
                }
                """);
    }

    @Test
    void editingUnknownCampaignIsNotFoundProblem() {
        var response = updateCampaign(999_999, SPRING_SALE);

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 404, "code": "CAMPAIGN_NOT_FOUND", "instance": "/admin/campaigns/999999" }
                """);
    }

    @Test
    void endAtNotAfterStartAtIsValidationProblemOnEndAt() {
        var response = createCampaign("""
                {
                  "name": "春節轉盤",
                  "startAt": "2027-02-10T00:00:00Z",
                  "endAt": "2027-02-10T00:00:00Z",
                  "drawQuota": 3
                }
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 400,
                  "code": "VALIDATION_FAILED",
                  "instance": "/admin/campaigns",
                  "errors": [ { "field": "endAt" } ]
                }
                """);
        assertThat(response).bodyJson().extractingPath("$.errors").asArray().hasSize(1);
    }

    @Test
    void nonPositiveDrawQuotaIsValidationProblemOnDrawQuota() {
        var response = createCampaign("""
                {
                  "name": "春節轉盤",
                  "startAt": "2027-01-20T00:00:00Z",
                  "endAt": "2027-02-10T23:59:59Z",
                  "drawQuota": 0
                }
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 400,
                  "code": "VALIDATION_FAILED",
                  "instance": "/admin/campaigns",
                  "errors": [ { "field": "drawQuota" } ]
                }
                """);
        assertThat(response).bodyJson().extractingPath("$.errors").asArray().hasSize(1);
    }

    @Test
    void missingFieldsAreReportedPerField() {
        var response = createCampaign("{}");

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("name", "startAt", "endAt", "drawQuota");
    }

    @Test
    void invalidEditIsValidationProblemAndLeavesCampaignUnchanged() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = updateCampaign(id, """
                {
                  "name": "春節轉盤",
                  "startAt": "2027-02-10T00:00:00Z",
                  "endAt": "2027-01-20T00:00:00Z",
                  "drawQuota": -1
                }
                """);

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactlyInAnyOrder("endAt", "drawQuota");
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange())
                .bodyJson().isLenientlyEqualTo("""
                        { "startAt": "2027-01-20T00:00:00Z", "endAt": "2027-02-10T23:59:59Z", "drawQuota": 3 }
                        """);
    }

    @Test
    void adminActivatesDraftCampaign() {
        long id = idOf(createCampaign(SPRING_SALE));
        addPrize(id);

        var response = transition(id, "activate");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "status": "ACTIVE" }
                """.formatted(id));
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange())
                .bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    }

    @Test
    void adminEndsActiveCampaign() {
        long id = idOf(createCampaign(SPRING_SALE));
        addPrize(id);
        assertThat(transition(id, "activate")).hasStatusOk();

        var response = transition(id, "end");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("ENDED");
    }

    @Test
    void adminEndsDraftCampaignDirectly() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = transition(id, "end");

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.status").isEqualTo("ENDED");
    }

    @Test
    void activatingCampaignWithoutPrizeIsRejected() {
        long id = idOf(createCampaign(SPRING_SALE));

        var response = transition(id, "activate");

        assertThat(response).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 409,
                  "code": "CAMPAIGN_HAS_NO_PRIZE",
                  "instance": "/admin/campaigns/%d/activate"
                }
                """.formatted(id));
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange())
                .bodyJson().extractingPath("$.status").isEqualTo("DRAFT");
    }

    @Test
    void activatingEndedCampaignIsInvalidStatusTransitionProblem() {
        long id = idOf(createCampaign(SPRING_SALE));
        transition(id, "end");

        var response = transition(id, "activate");

        assertThat(response).hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 409,
                  "code": "INVALID_STATUS_TRANSITION",
                  "instance": "/admin/campaigns/%d/activate"
                }
                """.formatted(id));
        assertThat(mvc.get().uri("/admin/campaigns/{id}", id).with(admin()).exchange())
                .bodyJson().extractingPath("$.status").isEqualTo("ENDED");
    }

    @Test
    void endingEndedCampaignIsInvalidStatusTransitionProblem() {
        long id = idOf(createCampaign(SPRING_SALE));
        transition(id, "end");

        var response = transition(id, "end");

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().extractingPath("$.code").isEqualTo("INVALID_STATUS_TRANSITION");
    }

    @Test
    void activatingActiveCampaignIsInvalidStatusTransitionProblem() {
        var response = transition(SEED_CAMPAIGN_ID, "activate");

        assertThat(response).hasStatus(HttpStatus.CONFLICT);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "status": 409,
                  "code": "INVALID_STATUS_TRANSITION",
                  "instance": "/admin/campaigns/1/activate"
                }
                """);
    }

    @Test
    void activatingUnknownCampaignIsNotFoundProblem() {
        var response = transition(999_999, "activate");

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).bodyJson().extractingPath("$.code").isEqualTo("CAMPAIGN_NOT_FOUND");
    }

    @Test
    void userIsForbiddenOnEveryAdminApi() {
        assertForbidden(mvc.get().uri("/admin/campaigns").with(user()).exchange(), "/admin/campaigns");
        assertForbidden(mvc.get().uri("/admin/campaigns/1").with(user()).exchange(), "/admin/campaigns/1");
        assertForbidden(mvc.post().uri("/admin/campaigns").with(user())
                .contentType(MediaType.APPLICATION_JSON).content(SPRING_SALE).exchange(), "/admin/campaigns");
        assertForbidden(mvc.put().uri("/admin/campaigns/1").with(user())
                .contentType(MediaType.APPLICATION_JSON).content(SPRING_SALE).exchange(), "/admin/campaigns/1");
        assertForbidden(mvc.post().uri("/admin/campaigns/1/activate").with(user()).exchange(),
                "/admin/campaigns/1/activate");
        assertForbidden(mvc.post().uri("/admin/campaigns/1/end").with(user()).exchange(),
                "/admin/campaigns/1/end");
        // USER 被拒後 seed Campaign 不受影響
        assertThat(mvc.get().uri("/admin/campaigns/1").with(admin()).exchange())
                .bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
    }

    @Test
    void anonymousRequestToAdminApiIsUnauthorizedProblem() {
        var response = mvc.get().uri("/admin/campaigns").exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 401, "code": "UNAUTHORIZED", "instance": "/admin/campaigns" }
                """);
    }

    private void assertForbidden(MvcTestResult response, String instance) {
        assertThat(response).hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 403, "code": "FORBIDDEN", "instance": "%s" }
                """.formatted(instance));
    }

    private void addPrize(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/prizes", campaignId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "免運券", "stock": 100, "winProbability": 30 }
                        """)
                .exchange()).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult transition(long id, String action) {
        return mvc.post().uri("/admin/campaigns/{id}/{action}", id, action).with(admin()).exchange();
    }

    private MvcTestResult updateCampaign(long id, String body) {
        return mvc.put().uri("/admin/campaigns/{id}", id).with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private MvcTestResult createCampaign(String body) {
        return mvc.post().uri("/admin/campaigns").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .exchange();
    }

    private static long idOf(MvcTestResult response) {
        try {
            Number id = JsonPath.read(response.getResponse().getContentAsString(), "$.id");
            return id.longValue();
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
