package com.spintowin.draw.draw.redis;

import com.spintowin.draw.draw.model.DrawRequestResult;

/** 抽獎 Lua 的判定結果。 */
public sealed interface DrawAttempt {

    /**
     * 已判定（或以相同 Idempotency-Key 重送，回傳先前的結果）。
     *
     * @param replayed true = 這是先前請求的結果，這次沒有扣減任何東西
     */
    record Drawn(DrawRequestResult result, boolean replayed) implements DrawAttempt {
    }

    /** Campaign 不在 Redis 中、不是 ACTIVE，或現在不在時間窗內。沒有扣減任何東西。 */
    record NotActive(Reason reason) implements DrawAttempt {

        public enum Reason { NOT_LOADED, NOT_ACTIVE, NOT_STARTED, ENDED }
    }

    /** 剩餘次數不足這次的 count，整筆拒絕。沒有扣減任何東西。 */
    record QuotaExceeded(int remainingDraws) implements DrawAttempt {
    }
}
