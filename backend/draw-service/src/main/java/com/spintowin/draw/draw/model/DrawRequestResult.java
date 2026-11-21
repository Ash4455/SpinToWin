package com.spintowin.draw.draw.model;

import java.time.Instant;
import java.util.List;

/**
 * 一次抽獎請求（單抽或 Multi-Draw）的結果。以相同 Idempotency-Key 重送時，回傳的是同一份結果。
 *
 * @param requestId      這次請求的識別；每一抽的 drawId 由它衍生
 * @param drawnAt        Redis 判定當下的時間
 * @param remainingDraws 判定後的剩餘 Draw 次數
 * @param results        依抽獎順序排列的 Draw Result
 */
public record DrawRequestResult(String requestId, Instant drawnAt, int remainingDraws, List<DrawResult> results) {
}
