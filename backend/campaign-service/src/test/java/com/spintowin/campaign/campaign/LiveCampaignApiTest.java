package com.spintowin.campaign.campaign;

import com.jayway.jsonpath.JsonPath;
import com.spintowin.campaign.campaign.service.LiveCampaignStartupLoader;
import com.spintowin.campaign.support.ApiTest;
import com.spintowin.common.redis.CampaignRedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Campaign 的 live state：上線時載入 Redis、上線期間的修改同步 Redis、可從 MySQL 重建。
 * 以 draw-service 的角度讀 Redis（key 規格見 {@link CampaignRedisKeys}）。
 */
class LiveCampaignApiTest extends ApiTest {

    private static final String SPRING_SALE = """
            {
              "name": "春節轉盤",
              "startAt": "2027-01-20T00:00:00Z",
              "endAt": "2027-02-10T23:59:59Z",
              "drawQuota": 3
            }
            """;

    /** draw-service 抽獎 Lua 對 Stock 的扣減方式：Stock > 0 才扣 1，回傳扣後剩餘量（-1 = 已無 Stock）。 */
    private static final RedisScript<Long> DRAW_ONE = RedisScript.of("""
            local left = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
            if left > 0 then return redis.call('HINCRBY', KEYS[1], ARGV[1], -1) end
            return -1
            """, Long.class);

    /** 服務啟動時執行的載入（ApplicationRunner）；測試中再次觸發以模擬重新啟動。 */
    @Autowired
    private LiveCampaignStartupLoader startupLoader;

    @Test
    void activationLoadsSettingsIntervalsAndStockIntoRedis() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long voucher = addPrize(campaignId, "100 元購物金", 200, "9.5");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");

        activate(campaignId);

        assertThat(meta(campaignId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ACTIVE",
                "startAt", "1800403200000",
                "endAt", "1802303999000",
                "drawQuota", "3"));
        assertThat(intervals(campaignId)).containsExactly(
                Map.entry(String.valueOf(headphones), 10_000L),
                Map.entry(String.valueOf(voucher), 105_000L),
                Map.entry(String.valueOf(shipping), 405_000L));
        assertThat(stock(campaignId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                String.valueOf(headphones), "10",
                String.valueOf(voucher), "200",
                String.valueOf(shipping), "1000"));
        assertThat(prizeNames(campaignId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                String.valueOf(headphones), "藍牙耳機",
                String.valueOf(voucher), "100 元購物金",
                String.valueOf(shipping), "免運券"));
        assertThat(redis.opsForSet().isMember(CampaignRedisKeys.LIVE_CAMPAIGNS, String.valueOf(campaignId)))
                .isTrue();
    }

    @Test
    void endingActiveCampaignMarksItEndedInRedisAndKeepsStock() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);

        end(campaignId);

        assertThat(meta(campaignId)).containsEntry("status", "ENDED").containsEntry("drawQuota", "3");
        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "10");
    }

    @Test
    void endingDraftCampaignWritesNothingToRedis() {
        long campaignId = createDraftCampaign();

        end(campaignId);

        assertThat(meta(campaignId)).isEmpty();
    }

    @Test
    void editingPrizeWhileActiveSyncsNameAndIntervalsButKeepsDrawnStock() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        drawOne(campaignId, headphones);

        assertThat(updatePrize(campaignId, headphones, "無線耳機", "2.5")).hasStatusOk();

        assertThat(intervals(campaignId)).containsExactly(
                Map.entry(String.valueOf(headphones), 25_000L),
                Map.entry(String.valueOf(shipping), 325_000L));
        assertThat(prizeNames(campaignId)).containsEntry(String.valueOf(headphones), "無線耳機");
        assertThat(stock(campaignId)).containsEntry(String.valueOf(headphones), "9");
    }

    @Test
    void prizeWithZeroProbabilityHasNoInterval() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        assertThat(updatePrize(campaignId, headphones, "藍牙耳機", "0")).hasStatusOk();

        assertThat(intervals(campaignId)).containsExactly(Map.entry(String.valueOf(shipping), 300_000L));
        assertThat(prizeNames(campaignId)).containsKeys(String.valueOf(headphones), String.valueOf(shipping));
    }

    @Test
    void rejectedEditLeavesRedisUnchanged() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "60");
        addPrize(campaignId, "免運券", 1000, "40");
        activate(campaignId);

        assertThat(updatePrize(campaignId, headphones, "無線耳機", "60.0001")).hasStatus(HttpStatus.CONFLICT);

        assertThat(intervals(campaignId)).containsEntry(String.valueOf(headphones), 600_000L);
        assertThat(prizeNames(campaignId)).containsEntry(String.valueOf(headphones), "藍牙耳機");
    }

    @Test
    void stockDeltaWhileActiveIsAddedToRedisWithoutOverwritingDrawnStock() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        drawOne(campaignId, prizeId);
        drawOne(campaignId, prizeId);
        drawOne(campaignId, prizeId);

        var response = adjustStock(campaignId, prizeId, 5);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().extractingPath("$.totalStock").isEqualTo(15);
        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "12");
    }

    @Test
    void stockDeltaWhileActiveIsJudgedAgainstRedisStock() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        for (int i = 0; i < 8; i++) {
            drawOne(campaignId, prizeId);
        }

        var response = adjustStock(campaignId, prizeId, -3);

        assertThat(response).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 409, "code": "STOCK_NEGATIVE", "instance": "/admin/campaigns/%d/prizes/%d/stock" }
                """.formatted(campaignId, prizeId));
        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "2");
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[0].totalStock").isEqualTo(10);
    }

    @Test
    void stockDeltaDownToExactlyZeroInRedisIsAllowed() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        for (int i = 0; i < 8; i++) {
            drawOne(campaignId, prizeId);
        }

        assertThat(adjustStock(campaignId, prizeId, -2)).hasStatusOk();

        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "0");
    }

    @Test
    void adminSeesTheLiveRedisStockOfAnActiveCampaign() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        drawOne(campaignId, prizeId);
        drawOne(campaignId, prizeId);
        drawOne(campaignId, prizeId);

        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[0].remainingStock").isEqualTo(7);
        assertThat(mvc.get().uri("/admin/campaigns").with(admin()).exchange()).bodyJson()
                .extractingPath("$[?(@.id == %d)].prizes[0].remainingStock".formatted(campaignId))
                .asArray().containsExactly(7);
    }

    @Test
    void stockDeltaAndPrizeEditResponsesReportTheLiveRedisStock() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        drawOne(campaignId, prizeId);
        drawOne(campaignId, prizeId);

        assertThat(adjustStock(campaignId, prizeId, 5)).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "totalStock": 15, "remainingStock": 13 }
                """.formatted(prizeId));
        assertThat(updatePrize(campaignId, prizeId, "無線耳機", "1")).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "name": "無線耳機", "totalStock": 15, "remainingStock": 13 }
                """.formatted(prizeId));
    }

    @Test
    void activeCampaignMissingFromRedisReportsTheMySqlStock() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        drawOne(campaignId, prizeId);
        loseLiveState(campaignId);

        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[0].remainingStock").isEqualTo(10);
    }

    @Test
    void concurrentStockDeltasAndDrawsLoseNoUpdates() throws Exception {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        int adminDeltas = 10;
        int draws = 500;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < adminDeltas; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    assertThat(adjustStock(campaignId, prizeId, 10)).hasStatusOk();
                    return null;
                }));
            }
            for (int i = 0; i < draws; i++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    drawOne(campaignId, prizeId);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "600");
        assertThat(getCampaign(campaignId)).bodyJson().extractingPath("$.prizes[0].totalStock").isEqualTo(1100);
    }

    // ---- 啟動時載入 ----

    @Test
    void activeSeedCampaignIsLoadedIntoRedisOnStartup() {
        assertThat(meta(1)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ACTIVE",
                "startAt", "1767225600000",
                "endAt", "1924991999000",
                "drawQuota", "10"));
        assertThat(intervals(1)).containsExactly(
                Map.entry("1", 10_000L),
                Map.entry("2", 105_000L),
                Map.entry("3", 405_000L));
        assertThat(prizeNames(1)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "1", "藍牙耳機", "2", "100 元購物金", "3", "免運券"));
        assertThat(stock(1)).containsExactlyInAnyOrderEntriesOf(Map.of("1", "10", "2", "200", "3", "1000"));
    }

    @Test
    void startupLoadFillsInMissingActiveCampaignsButKeepsLiveCountersOfLoadedOnes() {
        long drawnCampaign = createDraftCampaign();
        long drawnPrize = addPrize(drawnCampaign, "藍牙耳機", 10, "1");
        activate(drawnCampaign);
        drawOne(drawnCampaign, drawnPrize);
        long lostCampaign = createDraftCampaign();
        long lostPrize = addPrize(lostCampaign, "免運券", 1000, "30");
        activate(lostCampaign);
        loseLiveState(lostCampaign);

        startupLoader.loadMissingActiveCampaigns();

        assertThat(stock(drawnCampaign)).containsEntry(String.valueOf(drawnPrize), "9");
        assertThat(meta(lostCampaign)).containsEntry("status", "ACTIVE");
        assertThat(stock(lostCampaign)).containsEntry(String.valueOf(lostPrize), "1000");
    }

    // ---- 從 MySQL 重建 ----

    @Test
    void adminRebuildsLostLiveStateFromMySqlKeepingUsedDrawCounts() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        redis.opsForValue().set(CampaignRedisKeys.used(campaignId, USER_ID), "2");
        loseLiveState(campaignId);

        var response = rebuild(campaignId);

        assertThat(response).hasStatusOk();
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "id": %d, "status": "ACTIVE" }
                """.formatted(campaignId));
        assertThat(meta(campaignId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "status", "ACTIVE",
                "startAt", "1800403200000",
                "endAt", "1802303999000",
                "drawQuota", "3"));
        assertThat(intervals(campaignId)).containsExactly(
                Map.entry(String.valueOf(headphones), 10_000L),
                Map.entry(String.valueOf(shipping), 310_000L));
        assertThat(stock(campaignId)).containsExactlyInAnyOrderEntriesOf(Map.of(
                String.valueOf(headphones), "10",
                String.valueOf(shipping), "1000"));
        assertThat(prizeNames(campaignId)).hasSize(2);
        assertThat(redis.opsForValue().get(CampaignRedisKeys.used(campaignId, USER_ID))).isEqualTo("2");
    }

    @Test
    void changesMadeWhileLiveStateIsLostAreKeptInMySqlAndRebuilt() {
        long campaignId = createDraftCampaign();
        long prizeId = addPrize(campaignId, "藍牙耳機", 10, "1");
        activate(campaignId);
        loseLiveState(campaignId);

        assertThat(adjustStock(campaignId, prizeId, 5)).hasStatusOk();
        assertThat(updatePrize(campaignId, prizeId, "無線耳機", "2")).hasStatusOk();
        assertThat(meta(campaignId)).isEmpty();

        assertThat(rebuild(campaignId)).hasStatusOk();

        assertThat(stock(campaignId)).containsEntry(String.valueOf(prizeId), "15");
        assertThat(prizeNames(campaignId)).containsEntry(String.valueOf(prizeId), "無線耳機");
        assertThat(intervals(campaignId)).containsExactly(Map.entry(String.valueOf(prizeId), 20_000L));
    }

    @Test
    void rebuildingCampaignThatIsNotActiveIsRejected() {
        long campaignId = createDraftCampaign();

        var response = rebuild(campaignId);

        assertThat(response).hasStatus(HttpStatus.CONFLICT).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 409, "code": "CAMPAIGN_NOT_ACTIVE", "instance": "/admin/campaigns/%d/redis/rebuild" }
                """.formatted(campaignId));
        assertThat(meta(campaignId)).isEmpty();
    }

    @Test
    void rebuildingUnknownCampaignIsNotFound() {
        var response = rebuild(999_999);

        assertThat(response).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 404, "code": "CAMPAIGN_NOT_FOUND", "instance": "/admin/campaigns/999999/redis/rebuild" }
                """);
    }

    @Test
    void userIsForbiddenToRebuild() {
        var response = mvc.post().uri("/admin/campaigns/{id}/redis/rebuild", 1).with(user()).exchange();

        assertThat(response).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(response).bodyJson().isLenientlyEqualTo("""
                { "status": 403, "code": "FORBIDDEN", "instance": "/admin/campaigns/1/redis/rebuild" }
                """);
    }

    // ---- Redis 讀取（draw-service 的視角） ----

    /** 模擬 Redis 資料遺失：設定、區間、名稱與 Stock 都不見了。 */
    private void loseLiveState(long campaignId) {
        redis.delete(List.of(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.intervals(campaignId),
                CampaignRedisKeys.prizeNames(campaignId), CampaignRedisKeys.stock(campaignId)));
    }

    /** 模擬 draw-service 抽中一個 Prize：Stock > 0 時扣 1。 */
    private void drawOne(long campaignId, long prizeId) {
        Long left = redis.execute(DRAW_ONE, List.of(CampaignRedisKeys.stock(campaignId)), String.valueOf(prizeId));
        assertThat(left).isNotNegative();
    }

    private Map<Object, Object> meta(long campaignId) {
        return redis.opsForHash().entries(CampaignRedisKeys.meta(campaignId));
    }

    private Map<Object, Object> stock(long campaignId) {
        return redis.opsForHash().entries(CampaignRedisKeys.stock(campaignId));
    }

    private Map<Object, Object> prizeNames(long campaignId) {
        return redis.opsForHash().entries(CampaignRedisKeys.prizeNames(campaignId));
    }

    /** prizeId → 區間上界，依上界遞增（即 Prize 順序）。 */
    private Map<String, Long> intervals(long campaignId) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (TypedTuple<String> t : redis.opsForZSet().rangeWithScores(CampaignRedisKeys.intervals(campaignId), 0, -1)) {
            result.put(t.getValue(), t.getScore().longValue());
        }
        return result;
    }

    // ---- HTTP helpers ----

    private void activate(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/activate", campaignId).with(admin()).exchange())
                .hasStatusOk();
    }

    private MvcTestResult updatePrize(long campaignId, long prizeId, String name, String winProbability) {
        return mvc.put().uri("/admin/campaigns/{id}/prizes/{pid}", campaignId, prizeId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "%s", "winProbability": %s }
                        """.formatted(name, winProbability))
                .exchange();
    }

    private MvcTestResult adjustStock(long campaignId, long prizeId, int delta) {
        return mvc.patch().uri("/admin/campaigns/{id}/prizes/{pid}/stock", campaignId, prizeId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "delta": %d }
                        """.formatted(delta))
                .exchange();
    }

    private MvcTestResult rebuild(long campaignId) {
        return mvc.post().uri("/admin/campaigns/{id}/redis/rebuild", campaignId).with(admin()).exchange();
    }

    private MvcTestResult getCampaign(long campaignId) {
        return mvc.get().uri("/admin/campaigns/{id}", campaignId).with(admin()).exchange();
    }

    private void end(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/end", campaignId).with(admin()).exchange())
                .hasStatusOk();
    }

    private long createDraftCampaign() {
        return idOf(mvc.post().uri("/admin/campaigns").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content(SPRING_SALE)
                .exchange());
    }

    private long addPrize(long campaignId, String name, int stock, String winProbability) {
        return idOf(mvc.post().uri("/admin/campaigns/{id}/prizes", campaignId).with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "name": "%s", "stock": %d, "winProbability": %s }
                        """.formatted(name, stock, winProbability))
                .exchange());
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
