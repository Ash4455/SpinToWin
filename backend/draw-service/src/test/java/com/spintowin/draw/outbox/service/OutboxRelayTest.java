package com.spintowin.draw.outbox.service;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.redis.CampaignRedisKeys;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.service.DrawService;
import com.spintowin.draw.support.DrawTest;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Seam 3：抽獎 → Redis Stream outbox → relay → Kafka topic draw.completed（真 Redis + 真 Kafka）。
 * 以 {@link DrawService} 抽獎，只從 Kafka 觀察結果。
 */
class OutboxRelayTest extends DrawTest {

    /** 所有 draw-service 實例共用的 consumer group。 */
    private static final String CONSUMER_GROUP = "draw-outbox-relay";

    @Autowired
    DrawService drawService;

    @Autowired
    KafkaContainer kafka;

    @Test
    void successfulDrawIsPublishedToKafkaKeyedByCampaignWithEveryResult() throws Exception {
        long campaignId = campaigns.campaign()
                .prize(1, "藍牙耳機", 100_000, 10)
                .prize(2, "免運券", 200_000, 10)
                .load();
        String userId = newUserId();
        random.willReturn(50_000, 999_999, 250_000);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 3);

        String requestId = result.requestId();
        List<String> events = drawCompletedTopic.awaitJson(campaignId, 1);
        JSONAssert.assertEquals("""
                {"campaignId": %d, "userId": "%s", "requestId": "%s", "requestedAt": "%s",
                 "results": [
                   {"drawId": "%s-1", "prizeId": 1, "prizeName": "藍牙耳機"},
                   {"drawId": "%s-2", "prizeId": null, "prizeName": null},
                   {"drawId": "%s-3", "prizeId": 2, "prizeName": "免運券"}]}
                """.formatted(campaignId, userId, requestId, result.drawnAt(), requestId, requestId, requestId),
                events.getFirst(), JSONCompareMode.STRICT);
    }

    @Test
    void consumersCanReadTheEventWithTheJacksonJsonDeserializer() {
        long campaignId = campaigns.campaign().prize(7, "100 元購物金", 1_000_000, 5).load();
        String userId = newUserId();
        random.willReturn(10);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1)).containsExactly(new DrawCompletedEvent(
                campaignId, userId, result.requestId(), result.drawnAt(),
                List.of(new DrawCompletedEvent.Result(result.requestId() + "-1", 7L, "100 元購物金"))));
    }

    @Test
    void replayWithTheSameIdempotencyKeyDoesNotPublishASecondEvent() {
        long campaignId = campaigns.campaign().prize(1, "藍牙耳機", 500_000, 10).load();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(1, 2, 3);

        DrawRequestResult first = drawService.draw(new IdempotencyScope(campaignId, userId, key), 1);
        DrawRequestResult replay = drawService.draw(new IdempotencyScope(campaignId, userId, key), 1);
        // 第二個請求當作標記：同一 Campaign 的事件依序送出，標記到了，重送若有事件也一定已經到了
        DrawRequestResult marker = drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1);

        assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(drawCompletedTopic.awaitEvents(campaignId, 2))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(first.requestId(), marker.requestId());
    }

    @Test
    void rejectedDrawPublishesNothing() {
        long campaignId = campaigns.campaign().drawQuota(1).prize(1, "藍牙耳機", 500_000, 10).load();
        String userId = newUserId();
        random.willReturn(1, 2, 3);

        assertThatThrownBy(() -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 2))
                .isInstanceOf(BusinessException.class);
        DrawRequestResult marker = drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(marker.requestId());
    }

    @Test
    void deliveredEventsAreRemovedFromTheOutboxStream() {
        long campaignId = campaigns.campaign().prize(1, "藍牙耳機", 500_000, 10).load();
        random.willReturn(1, 2, 3);

        for (int i = 0; i < 3; i++) {
            drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);
        }

        drawCompletedTopic.awaitJson(campaignId, 3);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redis.opsForStream().size(CampaignRedisKeys.outbox(campaignId))).isZero());
    }

    @Test
    void drawsMadeJustBeforeTheCampaignEndsAreStillPublished() {
        long campaignId = campaigns.campaign().prize(1, "藍牙耳機", 500_000, 10).load();
        random.willReturn(1);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);
        campaigns.end(campaignId);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(result.requestId());
    }

    @Test
    void drawsAfterAnEndThatWasRolledBackAreStillPublished() throws InterruptedException {
        long campaignId = campaigns.campaign().prize(1, "藍牙耳機", 500_000, 10).load();
        random.willReturn(1);
        // campaign-service 先把 ENDED 寫進 Redis，MySQL commit 失敗後補償回 ACTIVE；
        // 中間 relay 有足夠時間（數個 claim-interval）看到「ENDED 且 outbox 為空」
        campaigns.end(campaignId);
        Thread.sleep(2_000);
        campaigns.reactivate(campaignId);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(result.requestId());
    }

    @Test
    void anUnreadableOutboxEntryIsDeadLetteredAndDoesNotHoldBackLaterEvents() {
        long campaignId = campaigns.campaign().unlisted().prize(1, "藍牙耳機", 500_000, 10).load();
        String outbox = CampaignRedisKeys.outbox(campaignId);
        redis.opsForStream().add(outbox, Map.of("event", "{not json"));
        random.willReturn(1);
        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        campaigns.list(campaignId);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(result.requestId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redis.opsForStream().size(outbox)).isZero());
        assertThat(redis.opsForStream().range(CampaignRedisKeys.outboxDeadLetter(campaignId), Range.unbounded()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getValue()).containsEntry("event", "{not json"));
    }

    @Test
    void eventsTakenByACrashedInstanceAreRedeliveredByAnotherInstance() {
        // relay 還看不到這個 Campaign 時，由「另一個實例」讀走事件後就崩潰（沒有 XACK）
        long campaignId = campaigns.campaign().unlisted().prize(1, "藍牙耳機", 500_000, 10).load();
        random.willReturn(1);
        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);
        String outbox = CampaignRedisKeys.outbox(campaignId);
        redis.opsForStream().createGroup(outbox, ReadOffset.from("0-0"), CONSUMER_GROUP);
        assertThat(redis.opsForStream().read(Consumer.from(CONSUMER_GROUP, "crashed-instance"),
                StreamOffset.create(outbox, ReadOffset.lastConsumed()))).hasSize(1);

        campaigns.list(campaignId);

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .containsExactly(result.requestId());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(redis.opsForStream().size(outbox)).isZero());
    }

    @Test
    void eventsThatCouldNotBeSentWhileKafkaWasDownAreRedeliveredAfterItRecovers() throws InterruptedException {
        long campaignId = campaigns.campaign().prize(1, "藍牙耳機", 500_000, 10).load();
        random.willReturn(1);
        DrawRequestResult result;

        pauseKafka();
        try {
            // Kafka 不可用時抽獎照常成功；relay 送出失敗（測試設定 delivery.timeout 2s），事件留在 pending
            result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);
            Thread.sleep(4_000);
        } finally {
            unpauseKafka();
        }

        assertThat(drawCompletedTopic.awaitEvents(campaignId, 1))
                .extracting(DrawCompletedEvent::requestId)
                .contains(result.requestId());
    }

    @Test
    void topicIsCreatedWithTheConfiguredPartitionCount() {
        assertThat(drawCompletedTopic.partitionCount()).isEqualTo(6);
    }

    private void pauseKafka() {
        kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
    }

    private void unpauseKafka() {
        kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
    }
}
