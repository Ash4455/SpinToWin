package com.spintowin.common.event;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * Kafka topic {@value #TOPIC} 的事件：一次成功的抽獎請求（單抽或 Multi-Draw）一筆，message key 為 campaignId。
 * <p>
 * draw-service 的抽獎 Lua 在扣減的同一個原子操作中把事件寫進 Redis Stream（outbox），再由 relay 轉送 Kafka。
 * 投遞是 <b>at-least-once</b>：同一筆事件可能送到不只一次，順序也不保證，消費端必須以
 * {@link Result#drawId()} 去重。以相同 Idempotency-Key 重送的請求不會產生新事件。
 * <p>
 * JSON（Jackson 3，不帶 type header）：
 * <pre>{@code
 * {"campaignId":1,"userId":"<JWT sub>","requestId":"<uuid>","requestedAt":"2026-11-21T08:00:00.123Z",
 *  "results":[{"drawId":"<uuid>-1","prizeId":3,"prizeName":"免運券"},
 *             {"drawId":"<uuid>-2","prizeId":null,"prizeName":null}]}
 * }</pre>
 *
 * @param campaignId  Campaign id（也是 Kafka message key）
 * @param userId      抽獎的使用者（JWT {@code sub}）
 * @param requestId   這次抽獎請求的識別
 * @param requestedAt Redis 判定這次請求的時間
 * @param results     依抽獎順序排列的 Draw Result，1..10 筆
 */
public record DrawCompletedEvent(long campaignId, String userId, String requestId, Instant requestedAt,
                                 List<Result> results) {

    public static final String TOPIC = "draw.completed";

    /**
     * 一次 Draw 的 Draw Result。
     *
     * @param drawId    這一抽的唯一識別（{@code <requestId>-<序號>}，序號從 1 開始），消費端以它去重
     * @param prizeId   抽中的 Prize；null = 銘謝惠顧
     * @param prizeName 抽中當下的 Prize 名稱；null = 銘謝惠顧
     */
    public record Result(String drawId, @Nullable Long prizeId, @Nullable String prizeName) {
    }
}
