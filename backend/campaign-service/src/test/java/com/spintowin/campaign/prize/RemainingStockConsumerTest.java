package com.spintowin.campaign.prize;

import com.jayway.jsonpath.JsonPath;
import com.spintowin.campaign.support.ApiTest;
import com.spintowin.campaign.support.DrawCompletedEvents;
import com.spintowin.common.redis.CampaignRedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import javax.sql.DataSource;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Seam 3：campaign-service 消費 draw.completed，最終把 MySQL 的 remaining_stock 扣到與 Redis 一致。
 * 事件以合約 JSON 直接送進 Kafka（模擬 draw-service），結果直接觀察 MySQL 的 prize.remaining_stock
 * （ACTIVE 時 ADMIN API 顯示的是 Redis 的即時 Stock，看不到 consumer 的產出）。
 */
class RemainingStockConsumerTest extends ApiTest {

    private static final Duration CONSUMED = Duration.ofSeconds(30);

    /** campaign-service 自己的 dead-letter topic（與 record-service 的分開）。 */
    private static final String DEAD_LETTER_TOPIC = "draw.completed.campaign-service.DLT";

    private static final String SPRING_SALE = """
            {
              "name": "春節轉盤",
              "startAt": "2027-01-20T00:00:00Z",
              "endAt": "2027-02-10T23:59:59Z",
              "drawQuota": 10
            }
            """;

    @Autowired
    private DrawCompletedEvents events;

    @Autowired
    private DataSource dataSource;

    @Test
    void wonPrizesAreDeductedFromRemainingStock() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        events.drawn(campaignId, shipping, null, headphones, shipping);

        awaitRemainingStock(campaignId, headphones, 9);
        awaitRemainingStock(campaignId, shipping, 998);
    }

    @Test
    void redeliveredEventIsDeductedOnlyOnce() throws Exception {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        String event = events.drawn(campaignId, headphones, headphones);
        events.send(campaignId, event);
        events.send(campaignId, event);
        // 同一 Campaign 的事件在同一個 partition 依序消費：marker 被扣減時，前面的重複事件都已處理過
        events.drawn(campaignId, shipping);

        awaitRemainingStock(campaignId, shipping, 999);
        assertThat(remainingStock(campaignId, headphones)).isEqualTo(8);
    }

    @Test
    void eventDeductingMoreThanRemainsStopsAtZeroAndDoesNotBlockLaterEvents() throws Exception {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 1, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        // 對帳異常：MySQL 的 remaining_stock 比事件抽走的少（正常情況下 Redis 不會讓它發生）
        events.drawn(campaignId, headphones, headphones, shipping);
        events.drawn(campaignId, shipping);

        awaitRemainingStock(campaignId, shipping, 998);
        assertThat(remainingStock(campaignId, headphones)).isZero();
    }

    @Test
    void eventForPrizeOfAnotherCampaignDoesNotTouchThatPrize() throws Exception {
        long campaignId = createDraftCampaign();
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        long otherCampaignId = createDraftCampaign();
        long otherPrize = addPrize(otherCampaignId, "藍牙耳機", 10, "1");
        activate(otherCampaignId);

        events.drawn(campaignId, otherPrize, shipping);

        awaitRemainingStock(campaignId, shipping, 999);
        assertThat(remainingStock(otherCampaignId, otherPrize)).isEqualTo(10);
    }

    @Test
    void unreadableMessageGoesToDeadLetterTopicWithoutBlockingLaterEvents() {
        long campaignId = createDraftCampaign();
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        events.send(campaignId, "{not json");
        events.drawn(campaignId, shipping);

        awaitRemainingStock(campaignId, shipping, 999);
        assertThat(events.awaitMessages(DEAD_LETTER_TOPIC, campaignId)).containsExactly("{not json");
    }

    @Test
    void eventBreakingTheContractGoesToDeadLetterTopicAndDeductsNothing() throws Exception {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        String withoutRequestId = """
                {"campaignId":%d,"userId":"u1","requestedAt":"2027-01-21T08:00:00Z",\
                "results":[{"drawId":"x-1","prizeId":%d,"prizeName":"藍牙耳機"}]}""".formatted(campaignId, headphones);

        events.send(campaignId, withoutRequestId);
        events.send(campaignId, withoutRequestId);
        events.drawn(campaignId, shipping);

        awaitRemainingStock(campaignId, shipping, 999);
        assertThat(remainingStock(campaignId, headphones)).isEqualTo(10);
        await().atMost(CONSUMED).untilAsserted(() ->
                assertThat(events.awaitMessages(DEAD_LETTER_TOPIC, campaignId)).hasSize(2));
    }

    @Test
    void consumingWhileAdminEditsAndAdjustsThePrizeLosesNoUpdates() throws Exception {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 1000, "1");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        int draws = 300;
        int edits = 40;
        int deltas = 40;

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> admin = new ArrayList<>();
            for (int i = 0; i < edits; i++) {
                String winProbability = i % 2 == 0 ? "1.5" : "1";
                admin.add(pool.submit(() -> assertThat(mvc.put()
                        .uri("/admin/campaigns/{id}/prizes/{pid}", campaignId, headphones).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "name": "藍牙耳機", "winProbability": %s }
                                """.formatted(winProbability))
                        .exchange()).hasStatusOk()));
            }
            for (int i = 0; i < deltas; i++) {
                admin.add(pool.submit(() -> assertThat(mvc.patch()
                        .uri("/admin/campaigns/{id}/prizes/{pid}/stock", campaignId, headphones).with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "delta": 1 }
                                """)
                        .exchange()).hasStatusOk()));
            }
            for (int i = 0; i < draws; i++) {
                events.drawn(campaignId, headphones);
            }
            for (Future<?> f : admin) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        events.drawn(campaignId, shipping);

        awaitRemainingStock(campaignId, shipping, 999);
        assertThat(remainingStock(campaignId, headphones)).isEqualTo(1000 - draws + deltas);
    }

    @Test
    void afterEventsAreConsumedRebuildRestoresTheStockRedisHadBeforeItWasLost() {
        long campaignId = createDraftCampaign();
        long headphones = addPrize(campaignId, "藍牙耳機", 10, "1");
        long voucher = addPrize(campaignId, "100 元購物金", 200, "9.5");
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);
        // draw-service 的行為：Lua 在 Redis 扣 Stock，並送出對應的事件
        drawInRedis(campaignId, headphones, voucher, voucher, shipping, shipping, shipping);
        events.drawn(campaignId, headphones, null, voucher);
        events.drawn(campaignId, voucher, shipping, shipping);
        events.drawn(campaignId, shipping);
        awaitRemainingStock(campaignId, shipping, 997);
        Map<Object, Object> liveStock = redis.opsForHash().entries(CampaignRedisKeys.stock(campaignId));
        assertThat(liveStock).containsExactlyInAnyOrderEntriesOf(Map.of(
                String.valueOf(headphones), "9",
                String.valueOf(voucher), "198",
                String.valueOf(shipping), "997"));

        redis.delete(List.of(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.intervals(campaignId),
                CampaignRedisKeys.prizeNames(campaignId), CampaignRedisKeys.stock(campaignId)));
        assertThat(mvc.post().uri("/admin/campaigns/{id}/redis/rebuild", campaignId).with(admin()).exchange())
                .hasStatusOk();

        assertThat(redis.opsForHash().entries(CampaignRedisKeys.stock(campaignId)))
                .containsExactlyInAnyOrderEntriesOf(liveStock);
    }

    @Test
    void eventIsAppliedOnceMySqlRecoversEvenAfterLongerThanTheBoundedRetries() throws Exception {
        long campaignId = createDraftCampaign();
        long shipping = addPrize(campaignId, "免運券", 1000, "30");
        activate(campaignId);

        // 故障注入：另一個交易長時間鎖住 Prize 列，consumer 的扣減一再遇到鎖等待逾時（暫時性的 MySQL 失敗）
        try (Connection lock = dataSource.getConnection()) {
            lock.setAutoCommit(false);
            try (PreparedStatement select = lock.prepareStatement("SELECT id FROM prize WHERE id = ? FOR UPDATE")) {
                select.setLong(1, shipping);
                select.executeQuery().close();
            }
            events.drawn(campaignId, shipping);
            // 比有限重試（3 次 × 2 秒鎖等待 + 2 × 1 秒間隔）更久：若被當成一般失敗，事件已進 dead-letter topic
            Thread.sleep(10_000);
            lock.rollback();
        }

        // 同一交易中的去重紀錄也隨失敗 rollback，所以重試時仍會扣減，而且只扣一次
        awaitRemainingStock(campaignId, shipping, 999);
    }

    // ---- helpers ----

    /** draw-service 抽獎 Lua 對 Stock 的扣減方式：Stock > 0 才扣 1，回傳扣後剩餘量（-1 = 已無 Stock）。 */
    private static final RedisScript<Long> DRAW_ONE = RedisScript.of("""
            local left = tonumber(redis.call('HGET', KEYS[1], ARGV[1]))
            if left > 0 then return redis.call('HINCRBY', KEYS[1], ARGV[1], -1) end
            return -1
            """, Long.class);

    private void drawInRedis(long campaignId, long... prizeIds) {
        for (long prizeId : prizeIds) {
            Long left = redis.execute(DRAW_ONE, List.of(CampaignRedisKeys.stock(campaignId)), String.valueOf(prizeId));
            assertThat(left).isNotNegative();
        }
    }

    private void awaitRemainingStock(long campaignId, long prizeId, int expected) {
        await().atMost(CONSUMED).untilAsserted(() -> assertThat(remainingStock(campaignId, prizeId)).isEqualTo(expected));
    }

    private int remainingStock(long campaignId, long prizeId) {
        return JdbcClient.create(dataSource)
                .sql("SELECT remaining_stock FROM prize WHERE id = ? AND campaign_id = ?")
                .params(prizeId, campaignId)
                .query(Integer.class)
                .single();
    }

    private void activate(long campaignId) {
        assertThat(mvc.post().uri("/admin/campaigns/{id}/activate", campaignId).with(admin()).exchange())
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
