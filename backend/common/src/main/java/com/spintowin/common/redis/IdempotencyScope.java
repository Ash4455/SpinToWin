package com.spintowin.common.redis;

/**
 * 一次抽獎請求的冪等範圍：同一位使用者在同一個 Campaign 以同一個 Idempotency-Key 送出的請求視為同一筆。
 * 不同使用者或不同 Campaign 使用相同的 key 互不影響。
 *
 * @param campaignId     Campaign id
 * @param userId         使用者 id（JWT subject）
 * @param idempotencyKey 用戶端送來的 Idempotency-Key
 */
public record IdempotencyScope(long campaignId, String userId, String idempotencyKey) {
}
