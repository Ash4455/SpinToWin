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

/** Seam 2：{@code GET /admin/campaigns/{id}/stats}，ADMIN 看各 Prize 抽出數、銘謝惠顧數與總抽獎次數。 */
class CampaignStatsApiTest extends RecordServiceTest {

    @Autowired
    DrawRecordService drawRecordService;

    @Test
    void statsCountEachPrizeNoWinAndTotalAcrossAllUsers() {
        long campaignId = newCampaignId();
        String alice = newUserId();
        String bob = newUserId();
        record(campaignId, alice, "a1", "2026-11-21T01:00:00Z",
                new Result("a1-1", 3L, "免運券"), new Result("a1-2", null, null), new Result("a1-3", 3L, "免運券"));
        record(campaignId, bob, "b1", "2026-11-21T02:00:00Z",
                new Result("b1-1", 1L, "藍牙耳機"), new Result("b1-2", null, null));
        record(campaignId, bob, "b2", "2026-11-21T03:00:00Z", new Result("b2-1", null, null));
        // 別的 Campaign 不算
        record(newCampaignId(), alice, "x1", "2026-11-21T03:00:00Z", new Result("x1-1", 3L, "免運券"));

        var response = mvc.get().uri("/admin/campaigns/{id}/stats", campaignId).with(admin()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isStrictlyEqualTo("""
                {
                  "campaignId": %d,
                  "totalDraws": 6,
                  "noWinCount": 3,
                  "prizes": [
                    {"prizeId": 1, "prizeName": "藍牙耳機", "drawnCount": 1},
                    {"prizeId": 3, "prizeName": "免運券", "drawnCount": 2}
                  ]
                }
                """.formatted(campaignId));
    }

    @Test
    void renamedPrizeIsCountedOnceUnderItsLatestName() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        // ACTIVE 期間 ADMIN 可改 Prize 名稱：事件帶的是抽中當下的名稱
        record(campaignId, userId, "late", "2026-11-21T05:00:00Z", new Result("late-1", 2L, "200 元購物金"));
        record(campaignId, userId, "early", "2026-11-21T01:00:00Z", new Result("early-1", 2L, "100 元購物金"),
                new Result("early-2", 2L, "100 元購物金"));

        var response = mvc.get().uri("/admin/campaigns/{id}/stats", campaignId).with(admin()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"totalDraws": 3, "noWinCount": 0,
                 "prizes": [{"prizeId": 2, "prizeName": "200 元購物金", "drawnCount": 3}]}
                """);
        assertThat(response).bodyJson().extractingPath("$.prizes").asArray().hasSize(1);
    }

    @Test
    void campaignWithoutRecordsHasZeroStats() {
        long campaignId = newCampaignId();

        var response = mvc.get().uri("/admin/campaigns/{id}/stats", campaignId).with(admin()).exchange();

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isStrictlyEqualTo("""
                {"campaignId": %d, "totalDraws": 0, "noWinCount": 0, "prizes": []}
                """.formatted(campaignId));
    }

    @Test
    void userIsForbiddenProblem() {
        var response = mvc.get().uri("/admin/campaigns/1/stats").with(user(newUserId())).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 403, "title": "Forbidden", "code": "FORBIDDEN", "instance": "/admin/campaigns/1/stats"}
                """);
    }

    @Test
    void anonymousRequestIsUnauthorizedProblem() {
        var response = mvc.get().uri("/admin/campaigns/1/stats").exchange();

        assertThat(response).hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader(HttpHeaders.WWW_AUTHENTICATE);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                {"status": 401, "code": "UNAUTHORIZED", "instance": "/admin/campaigns/1/stats"}
                """);
    }

    private void record(long campaignId, String userId, String requestId, String requestedAt, Result... results) {
        drawRecordService.record(new DrawCompletedEvent(campaignId, userId, requestId, Instant.parse(requestedAt),
                List.of(results)));
    }
}
