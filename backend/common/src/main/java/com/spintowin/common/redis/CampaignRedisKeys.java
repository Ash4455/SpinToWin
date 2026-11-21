package com.spintowin.common.redis;

/**
 * Campaign 在 Redis 中的即時狀態（live state）的 key 規格：campaign-service 寫入，draw-service 的抽獎 Lua 讀寫。
 * <p>
 * 每個 key 都以 {@code {campaignId}} 作為 hash tag（例如 {@code campaign:{42}:stock}），
 * 同一 Campaign 的所有 key（包含以使用者 id 組成的 key）落在同一個 slot，單一 Lua script 可在 Redis Cluster 上存取。
 * 唯一例外是 {@link #LIVE_CAMPAIGNS}：它是全域索引，抽獎 Lua 不會碰它。
 *
 * <table>
 *   <caption>Key 一覽</caption>
 *   <tr><th>key</th><th>型別</th><th>內容</th></tr>
 *   <tr><td>{@link #meta}</td><td>HASH</td>
 *       <td>{@value #META_STATUS}（ACTIVE / ENDED）、{@value #META_START_AT}、{@value #META_END_AT}
 *       （epoch 毫秒，時間窗為 [startAt, endAt]）、{@value #META_DRAW_QUOTA}（每位使用者的 Draw Quota）</td></tr>
 *   <tr><td>{@link #intervals}</td><td>ZSET</td>
 *       <td>member = prizeId，score = 該 Prize ppm 區間的上界（不含）。區間依 Prize 順序（id 遞增）累加：
 *       第一個 Prize 是 [0, ppm1)，下一個是 [ppm1, ppm1 + ppm2)…。ppm 為 0 的 Prize 不在其中。
 *       隨機數 r ∈ [0, 1,000,000) 落在 {@code ZRANGE key (r +inf BYSCORE LIMIT 0 1} 的 Prize；查無結果即銘謝惠顧。</td></tr>
 *   <tr><td>{@link #prizeNames}</td><td>HASH</td><td>prizeId → Prize 名稱（包含 ppm 為 0 的 Prize）</td></tr>
 *   <tr><td>{@link #stock}</td><td>HASH</td><td>prizeId → 剩餘 Stock（整數，永不為負）</td></tr>
 *   <tr><td>{@link #used}</td><td>STRING</td><td>該使用者在此 Campaign 已用的 Draw 次數（不存在視為 0）</td></tr>
 *   <tr><td>{@link #idempotency}</td><td>STRING</td><td>該使用者某個 Idempotency-Key 的抽獎結果（TTL 24h）</td></tr>
 *   <tr><td>{@link #outbox}</td><td>STREAM</td><td>draw.completed 事件的 outbox</td></tr>
 *   <tr><td>{@link #outboxDeadLetter}</td><td>STREAM</td>
 *       <td>無法解析、因此無法送出的 outbox 事件（原始欄位加上 {@code outboxId}），供人工檢查</td></tr>
 *   <tr><td>{@link #LIVE_CAMPAIGNS}</td><td>SET</td><td>曾載入 Redis 的 campaignId（outbox relay 據此找 stream）</td></tr>
 * </table>
 */
public final class CampaignRedisKeys {

    /** 曾載入 Redis 的 campaignId 集合（全域索引，不帶 hash tag）。 */
    public static final String LIVE_CAMPAIGNS = "campaigns:live";

    public static final String META_STATUS = "status";
    public static final String META_START_AT = "startAt";
    public static final String META_END_AT = "endAt";
    public static final String META_DRAW_QUOTA = "drawQuota";

    private CampaignRedisKeys() {
    }

    /** HASH：Campaign Status、時間窗與 Draw Quota。 */
    public static String meta(long campaignId) {
        return prefix(campaignId) + "meta";
    }

    /** ZSET：依 Prize 順序累加的 ppm 區間上界。 */
    public static String intervals(long campaignId) {
        return prefix(campaignId) + "intervals";
    }

    /** HASH：prizeId → Prize 名稱。 */
    public static String prizeNames(long campaignId) {
        return prefix(campaignId) + "prize-names";
    }

    /** HASH：prizeId → 剩餘 Stock。 */
    public static String stock(long campaignId) {
        return prefix(campaignId) + "stock";
    }

    /** STRING：使用者已用的 Draw 次數。 */
    public static String used(long campaignId, String userId) {
        return prefix(campaignId) + "used:" + userId;
    }

    /** STRING：使用者某個 Idempotency-Key 的抽獎結果。 */
    public static String idempotency(IdempotencyScope scope) {
        return prefix(scope.campaignId()) + "idem:" + scope.userId() + ":" + scope.idempotencyKey();
    }

    /** STREAM：draw.completed 事件的 outbox。 */
    public static String outbox(long campaignId) {
        return prefix(campaignId) + "outbox";
    }

    /** STREAM：無法解析的 outbox 事件（outbox relay 移到這裡，不再阻塞 outbox）。 */
    public static String outboxDeadLetter(long campaignId) {
        return prefix(campaignId) + "outbox:dead";
    }

    private static String prefix(long campaignId) {
        return "campaign:{" + campaignId + "}:";
    }
}
