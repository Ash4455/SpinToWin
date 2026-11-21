package com.spintowin.campaign.campaign.redis;

/**
 * 在 Redis 套用 Stock 增減量的結果。
 *
 * @param remaining {@link Outcome#APPLIED} 時為調整後的剩餘量；{@link Outcome#WOULD_BE_NEGATIVE} 時為目前的剩餘量
 */
public record StockAdjustment(Outcome outcome, long remaining) {

    public enum Outcome {
        /** 已套用。 */
        APPLIED,
        /** 套用後會變成負數，Redis 未改變。 */
        WOULD_BE_NEGATIVE,
        /** 這個 Prize 不在 Redis 中（Campaign 未載入），Redis 未改變。 */
        NOT_LOADED
    }
}
