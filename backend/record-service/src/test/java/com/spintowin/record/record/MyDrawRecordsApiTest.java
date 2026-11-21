package com.spintowin.record.record;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.event.DrawCompletedEvent.Result;
import com.spintowin.record.record.service.DrawRecordService;
import com.spintowin.record.support.RecordServiceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Seam 2：{@code GET /campaigns/{id}/draws/me}，USER 查自己在某 Campaign 的 Draw Result（新到舊、分頁）。 */
class MyDrawRecordsApiTest extends RecordServiceTest {

    /** 紀錄由 consumer 使用的同一個 service 寫入（同步），API 測試不必等 Kafka。 */
    @Autowired
    DrawRecordService drawRecordService;

    @Test
    void myRecordsAreNewestFirstAndPaginated() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        record(campaignId, userId, "req-a", "2026-11-21T01:00:00Z",
                new Result("req-a-1", 3L, "免運券"), new Result("req-a-2", null, null));
        record(campaignId, userId, "req-c", "2026-11-21T03:00:00Z",
                new Result("req-c-1", null, null), new Result("req-c-2", 2L, "100 元購物金"),
                new Result("req-c-3", null, null));
        record(campaignId, userId, "req-b", "2026-11-21T02:00:00Z", new Result("req-b-1", 1L, "藍牙耳機"));

        var firstPage = mvc.get().uri("/campaigns/{id}/draws/me?page=0&size=4", campaignId)
                .with(user(userId)).exchange();

        assertThat(firstPage).hasStatusOk();
        assertThat(firstPage).bodyJson().isLenientlyEqualTo("""
                {
                  "content": [
                    {"drawId": "req-c-3", "requestId": "req-c", "prizeId": null, "prizeName": null,
                     "won": false, "drawnAt": "2026-11-21T03:00:00Z"},
                    {"drawId": "req-c-2", "requestId": "req-c", "prizeId": 2, "prizeName": "100 元購物金",
                     "won": true, "drawnAt": "2026-11-21T03:00:00Z"},
                    {"drawId": "req-c-1", "requestId": "req-c", "prizeId": null, "prizeName": null,
                     "won": false, "drawnAt": "2026-11-21T03:00:00Z"},
                    {"drawId": "req-b-1", "requestId": "req-b", "prizeId": 1, "prizeName": "藍牙耳機",
                     "won": true, "drawnAt": "2026-11-21T02:00:00Z"}
                  ],
                  "page": 0,
                  "size": 4,
                  "totalElements": 6,
                  "totalPages": 2
                }
                """);
        assertThat(firstPage).bodyJson().extractingPath("$.content[*].drawId").asArray()
                .containsExactly("req-c-3", "req-c-2", "req-c-1", "req-b-1");

        var secondPage = mvc.get().uri("/campaigns/{id}/draws/me?page=1&size=4", campaignId)
                .with(user(userId)).exchange();

        assertThat(secondPage).hasStatusOk();
        assertThat(secondPage).bodyJson().extractingPath("$.content[*].drawId").asArray()
                .containsExactly("req-a-2", "req-a-1");
        assertThat(secondPage).bodyJson().isLenientlyEqualTo("""
                {"page": 1, "size": 4, "totalElements": 6, "totalPages": 2}
                """);
    }

    @Test
    void defaultPageIsTheFirstTwentyRecords() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        for (int i = 1; i <= 3; i++) {
            record(campaignId, userId, "req-" + i, "2026-11-21T0" + i + ":00:00Z",
                    new Result("req-" + i + "-1", null, null));
        }

        var response = mvc.get().uri("/campaigns/{id}/draws/me", campaignId).with(user(userId)).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"page": 0, "size": 20, "totalElements": 3, "totalPages": 1}
                """);
        assertThat(response).bodyJson().extractingPath("$.content[*].drawId").asArray()
                .containsExactly("req-3-1", "req-2-1", "req-1-1");
    }

    @Test
    void otherUsersRecordsAndOtherCampaignsAreNeverShown() {
        long campaignId = newCampaignId();
        long otherCampaignId = newCampaignId();
        String me = newUserId();
        String someoneElse = newUserId();
        record(campaignId, me, "mine", "2026-11-21T01:00:00Z", new Result("mine-1", 3L, "免運券"));
        record(campaignId, someoneElse, "theirs", "2026-11-21T02:00:00Z", new Result("theirs-1", 1L, "藍牙耳機"));
        record(otherCampaignId, me, "elsewhere", "2026-11-21T03:00:00Z", new Result("elsewhere-1", null, null));

        var response = mvc.get().uri("/campaigns/{id}/draws/me", campaignId).with(user(me)).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.content[*].drawId").asArray().containsExactly("mine-1");
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"totalElements": 1}
                """);
    }

    @Test
    void userWithoutRecordsGetsAnEmptyPage() {
        var response = mvc.get().uri("/campaigns/{id}/draws/me", newCampaignId()).with(user(newUserId())).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isStrictlyEqualTo("""
                {"content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0}
                """);
    }

    @Test
    void pageSizeOutsideOneToOneHundredIsValidationProblem() {
        for (String size : List.of("0", "101")) {
            var response = mvc.get().uri("/campaigns/1/draws/me?size={size}", size)
                    .with(user(newUserId())).exchange();

            assertThat(response).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response).bodyJson().isLenientlyEqualTo("""
                    {"status": 400, "code": "VALIDATION_FAILED", "instance": "/campaigns/1/draws/me",
                     "errors": [{"field": "size"}]}
                    """);
        }
    }

    @Test
    void negativePageIsValidationProblem() {
        var response = mvc.get().uri("/campaigns/1/draws/me?page=-1").with(user(newUserId())).exchange();

        assertThat(response).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 400, "code": "VALIDATION_FAILED", "instance": "/campaigns/1/draws/me",
                 "errors": [{"field": "page"}]}
                """);
    }

    @Test
    void anonymousRequestIsUnauthorizedProblem() {
        var response = mvc.get().uri("/campaigns/1/draws/me").exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 401, "code": "UNAUTHORIZED", "instance": "/campaigns/1/draws/me"}
                """);
    }

    @Test
    void tokenWithoutUserRoleIsForbiddenProblem() {
        var response = mvc.get().uri("/campaigns/1/draws/me").with(admin()).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 403, "code": "FORBIDDEN", "instance": "/campaigns/1/draws/me"}
                """);
    }

    private void record(long campaignId, String userId, String requestId, String requestedAt, Result... results) {
        drawRecordService.record(new DrawCompletedEvent(campaignId, userId, requestId, Instant.parse(requestedAt),
                List.of(results)));
    }
}
