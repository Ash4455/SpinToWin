package com.spintowin.draw.draw;

import com.spintowin.draw.support.DrawTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP API：{@code POST /campaigns/{id}/draws} 與 {@code GET /campaigns/{id}/me}。
 * mock JWT 的 subject 就是使用者 id；錯誤一律是 Problem Details。
 */
class DrawApiTest extends DrawTest {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private long seedLikeCampaign() {
        return campaigns.campaign().drawQuota(10)
                .prize(1, "藍牙耳機", 10_000, 10)
                .prize(2, "100 元購物金", 95_000, 200)
                .prize(3, "免運券", 300_000, 1000)
                .load();
    }

    @Test
    void userMultiDrawsAndGetsEveryResultWithRemainingDraws() {
        long campaignId = seedLikeCampaign();
        random.willReturn(0, 500_000, 200_000);

        var response = mvc.post().uri("/campaigns/{id}/draws", campaignId)
                .with(user(newUserId()))
                .header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count": 3}
                        """)
                .exchange();

        assertThat(response).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {
                  "remainingDraws": 7,
                  "results": [
                    {"prizeId": 1,    "prizeName": "藍牙耳機", "won": true},
                    {"prizeId": null, "prizeName": null,      "won": false},
                    {"prizeId": 3,    "prizeName": "免運券",   "won": true}
                  ]
                }
                """);
        assertThat(response).bodyJson().extractingPath("$.results[*].prizeId").asArray()
                .containsExactly(1, null, 3);
        assertThat(response).bodyJson().extractingPath("$.requestId").asString().isNotBlank();
        assertThat(response).bodyJson().extractingPath("$.drawnAt").asString().isNotBlank();
        assertThat(response).bodyJson().extractingPath("$.results[*].drawId").asArray()
                .doesNotContainNull().doesNotHaveDuplicates();
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsTheSameBody() throws Exception {
        long campaignId = seedLikeCampaign();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(0, 999_999);

        var first = draw(campaignId, userId, key, 1);
        var retried = draw(campaignId, userId, key, 1);

        assertThat(retried).hasStatusOk();
        assertThat(retried).bodyText().isEqualTo(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(me(campaignId, userId)).bodyJson().isLenientlyEqualTo("""
                {"usedDraws": 1, "remainingDraws": 9}
                """);
    }

    @Test
    void meReportsQuotaOfTheTokenSubjectOnly() {
        long campaignId = seedLikeCampaign();
        String alice = newUserId();
        random.willReturn(1, 2, 3, 4);
        draw(campaignId, alice, newIdempotencyKey(), 4);

        var aliceMe = me(campaignId, alice);
        var bobMe = me(campaignId, newUserId());

        assertThat(aliceMe).hasStatusOk();
        assertThat(aliceMe).bodyJson().isLenientlyEqualTo("""
                {"campaignId": %d, "drawQuota": 10, "usedDraws": 4, "remainingDraws": 6}
                """.formatted(campaignId));
        assertThat(bobMe).bodyJson().isLenientlyEqualTo("""
                {"campaignId": %d, "drawQuota": 10, "usedDraws": 0, "remainingDraws": 10}
                """.formatted(campaignId));
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        long campaignId = seedLikeCampaign();

        var response = mvc.post().uri("/campaigns/{id}/draws", campaignId)
                .with(user(newUserId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count": 1}
                        """)
                .exchange();

        assertProblem(response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                "/campaigns/" + campaignId + "/draws");
    }

    @Test
    void blankIdempotencyKeyIsRejected() {
        long campaignId = seedLikeCampaign();

        var response = draw(campaignId, newUserId(), "  ", 1);

        assertProblem(response, HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED",
                "/campaigns/" + campaignId + "/draws");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 11, -1})
    void countOutside1To10IsValidationProblemOnCount(int count) {
        long campaignId = seedLikeCampaign();

        var response = draw(campaignId, newUserId(), newIdempotencyKey(), count);

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "/campaigns/" + campaignId + "/draws");
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("count");
    }

    @Test
    void missingCountIsValidationProblemOnCount() {
        long campaignId = seedLikeCampaign();

        var response = mvc.post().uri("/campaigns/{id}/draws", campaignId)
                .with(user(newUserId()))
                .header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .exchange();

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "/campaigns/" + campaignId + "/draws");
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray().containsExactly("count");
    }

    @Test
    void idempotencyKeyLongerThan128CharactersIsValidationProblem() {
        long campaignId = seedLikeCampaign();

        var response = draw(campaignId, newUserId(), "k".repeat(129), 1);

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "/campaigns/" + campaignId + "/draws");
        assertThat(response).bodyJson().extractingPath("$.errors[*].field").asArray()
                .containsExactly("Idempotency-Key");
    }

    @Test
    void multiDrawLargerThanRemainingQuotaIsQuotaExceededProblem() {
        long campaignId = campaigns.campaign().drawQuota(2).prize(1, "藍牙耳機", 10_000, 10).load();
        random.willReturn(1, 2, 3);

        var response = draw(campaignId, newUserId(), newIdempotencyKey(), 3);

        assertProblem(response, HttpStatus.CONFLICT, "QUOTA_EXCEEDED", "/campaigns/" + campaignId + "/draws");
    }

    @Test
    void endedCampaignIsNotActiveProblem() {
        long campaignId = seedLikeCampaign();
        campaigns.end(campaignId);
        random.willReturn(0);

        var response = draw(campaignId, newUserId(), newIdempotencyKey(), 1);

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_ACTIVE", "/campaigns/" + campaignId + "/draws");
    }

    @Test
    void meOfCampaignNotInRedisIsNotActiveProblem() {
        var response = me(987_654_321L, newUserId());

        assertProblem(response, HttpStatus.CONFLICT, "CAMPAIGN_NOT_ACTIVE", "/campaigns/987654321/me");
    }

    @Test
    void nonNumericCampaignIdIsValidationProblem() {
        var response = mvc.get().uri("/campaigns/{id}/me", "abc").with(user(newUserId())).exchange();

        assertProblem(response, HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "/campaigns/abc/me");
    }

    @Test
    void anonymousDrawIsUnauthorizedProblem() {
        long campaignId = seedLikeCampaign();

        var response = mvc.post().uri("/campaigns/{id}/draws", campaignId)
                .header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count": 1}
                        """)
                .exchange();

        assertThat(response).containsHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertProblem(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "/campaigns/" + campaignId + "/draws");
    }

    @Test
    void tokenWithoutUserRoleIsForbiddenProblem() {
        long campaignId = seedLikeCampaign();

        var response = mvc.get().uri("/campaigns/{id}/me", campaignId)
                .with(keycloakJwt(newUserId(), "ADMIN"))
                .exchange();

        assertProblem(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "/campaigns/" + campaignId + "/me");
    }

    @Test
    void healthIsPublic() {
        var response = mvc.get().uri("/actuator/health").exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": "UP"}
                """);
    }

    private MvcTestResult draw(
            long campaignId, String userId, String idempotencyKey, int count) {
        return mvc.post().uri("/campaigns/{id}/draws", campaignId)
                .with(user(userId))
                .header(IDEMPOTENCY_KEY, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"count": %d}
                        """.formatted(count))
                .exchange();
    }

    private MvcTestResult me(long campaignId, String userId) {
        return mvc.get().uri("/campaigns/{id}/me", campaignId).with(user(userId)).exchange();
    }

    private static void assertProblem(MvcTestResult response,
                                      HttpStatus status, String code, String instance) {
        assertThat(response).hasStatus(status).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": %d, "code": "%s", "instance": "%s"}
                """.formatted(status.value(), code, instance));
    }
}
