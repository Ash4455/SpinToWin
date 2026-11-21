package com.spintowin.record.record;

import com.spintowin.record.support.DrawRecordTable;
import com.spintowin.record.support.DrawRecordTable.Row;
import com.spintowin.record.support.RecordServiceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

/**
 * Seam 3：把 {@code draw.completed} 事件（JSON 合約）送進真 Kafka，觀察 record schema 的 DB 狀態。
 */
@Import(DrawRecordTable.class)
class DrawCompletedConsumerTest extends RecordServiceTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    DrawRecordTable table;

    @Test
    void eachResultOfAnEventBecomesOneRecord() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        String requestId = newRequestId();

        topic.publish(campaignId, """
                {"campaignId":%d,"userId":"%s","requestId":"%s","requestedAt":"2026-11-21T02:26:41.731Z",
                 "results":[{"drawId":"%3$s-1","prizeId":null,"prizeName":null},
                            {"drawId":"%3$s-2","prizeId":3,"prizeName":"免運券"},
                            {"drawId":"%3$s-3","prizeId":1,"prizeName":"藍牙耳機"}]}
                """.formatted(campaignId, userId, requestId));

        Instant drawnAt = Instant.parse("2026-11-21T02:26:41.731Z");
        assertThat(awaitRows(campaignId, 3)).containsExactly(
                new Row(requestId + "-1", requestId, userId, null, null, drawnAt),
                new Row(requestId + "-2", requestId, userId, 3L, "免運券", drawnAt),
                new Row(requestId + "-3", requestId, userId, 1L, "藍牙耳機", drawnAt));
    }

    @Test
    void redeliveredEventIsRecordedOnlyOnce() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        String requestId = newRequestId();
        String event = twoResultEvent(campaignId, userId, requestId);

        topic.publish(campaignId, event);
        topic.publish(campaignId, event);
        // 同一 Campaign = 同一 partition，依序消費：marker 被寫入時，前面兩筆重複事件一定已處理完
        String markerRequestId = newRequestId();
        topic.publish(campaignId, oneResultEvent(campaignId, userId, markerRequestId));

        // 列是依 draw_id 排序，而 requestId 是隨機 UUID，所以不比順序
        assertThat(awaitRows(campaignId, 3)).extracting(Row::drawId)
                .containsExactlyInAnyOrder(requestId + "-1", requestId + "-2", markerRequestId + "-1");
    }

    @Test
    void eventWhoseResultsArePartlyRecordedAddsOnlyTheMissingResults() {
        long campaignId = newCampaignId();
        String userId = newUserId();
        String requestId = newRequestId();
        // 先只有第 1 個結果落庫（例如之前的處理只寫到一半），之後完整事件再送達
        topic.publish(campaignId, """
                {"campaignId":%d,"userId":"%s","requestId":"%s","requestedAt":"2026-11-21T03:00:00Z",
                 "results":[{"drawId":"%3$s-1","prizeId":2,"prizeName":"100 元購物金"}]}
                """.formatted(campaignId, userId, requestId));
        awaitRows(campaignId, 1);

        topic.publish(campaignId, """
                {"campaignId":%d,"userId":"%s","requestId":"%s","requestedAt":"2026-11-21T03:00:00Z",
                 "results":[{"drawId":"%3$s-1","prizeId":2,"prizeName":"100 元購物金"},
                            {"drawId":"%3$s-2","prizeId":null,"prizeName":null},
                            {"drawId":"%3$s-3","prizeId":3,"prizeName":"免運券"}]}
                """.formatted(campaignId, userId, requestId));

        assertThat(awaitRows(campaignId, 3)).extracting(Row::drawId, Row::prizeName).containsExactly(
                tuple(requestId + "-1", "100 元購物金"),
                tuple(requestId + "-2", null),
                tuple(requestId + "-3", "免運券"));
    }

    static Stream<Arguments> unprocessableEvents() {
        String tooLongName = "獎".repeat(101);
        return Stream.of(
                Arguments.of("not JSON", "this is not json"),
                Arguments.of("missing userId", """
                        {"campaignId":%d,"requestId":"r-1","requestedAt":"2026-11-21T03:00:00Z",
                         "results":[{"drawId":"r-1-1","prizeId":null,"prizeName":null}]}"""),
                Arguments.of("no results", """
                        {"campaignId":%d,"userId":"u","requestId":"r-2","requestedAt":"2026-11-21T03:00:00Z",
                         "results":[]}"""),
                Arguments.of("prizeId without prizeName", """
                        {"campaignId":%d,"userId":"u","requestId":"r-3","requestedAt":"2026-11-21T03:00:00Z",
                         "results":[{"drawId":"r-3-1","prizeId":3,"prizeName":null}]}"""),
                // 能解析、但寫入資料庫失敗（欄位超長）：有限次重試後才放棄
                Arguments.of("prize name longer than the column", """
                        {"campaignId":%%d,"userId":"u","requestId":"r-4","requestedAt":"2026-11-21T03:00:00Z",
                         "results":[{"drawId":"r-4-1","prizeId":3,"prizeName":"%s"}]}""".formatted(tooLongName)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unprocessableEvents")
    void unprocessableEventGoesToDeadLetterTopicWithoutBlockingLaterEvents(String description, String template) {
        long campaignId = newCampaignId();
        String poison = template.contains("%d") ? template.formatted(campaignId) : template;
        String userId = newUserId();
        String laterRequestId = newRequestId();

        topic.publish(campaignId, poison);
        topic.publish(campaignId, oneResultEvent(campaignId, userId, laterRequestId));

        assertThat(awaitRows(campaignId, 1)).extracting(Row::drawId).containsExactly(laterRequestId + "-1");
        assertThat(topic.awaitDeadLetters(campaignId, 1)).containsExactly(poison);
    }

    private static String oneResultEvent(long campaignId, String userId, String requestId) {
        return """
                {"campaignId":%d,"userId":"%s","requestId":"%s","requestedAt":"2026-11-21T04:00:00Z",
                 "results":[{"drawId":"%3$s-1","prizeId":null,"prizeName":null}]}
                """.formatted(campaignId, userId, requestId);
    }

    private static String twoResultEvent(long campaignId, String userId, String requestId) {
        return """
                {"campaignId":%d,"userId":"%s","requestId":"%s","requestedAt":"2026-11-21T03:30:00Z",
                 "results":[{"drawId":"%3$s-1","prizeId":3,"prizeName":"免運券"},
                            {"drawId":"%3$s-2","prizeId":null,"prizeName":null}]}
                """.formatted(campaignId, userId, requestId);
    }

    private List<Row> awaitRows(long campaignId, int count) {
        return await().atMost(TIMEOUT).until(() -> table.rows(campaignId), rows -> rows.size() >= count);
    }
}
