package com.spintowin.campaign.prize.dto;

import com.spintowin.campaign.prize.entity.Prize;

import java.math.BigDecimal;
import java.util.Map;

/** ADMIN 看到的 Prize：Win Probability（百分比）、總 Stock 與剩餘 Stock。 */
public record AdminPrizeResponse(
        Long id,
        String name,
        BigDecimal winProbability,
        int totalStock,
        int remainingStock) {

    /** remainingStock 用 MySQL 的值。 */
    public static AdminPrizeResponse from(Prize prize) {
        return from(prize, Map.of());
    }

    /** @param liveStock prizeId → 即時剩餘 Stock（ACTIVE 時取自 Redis）；沒有這個 Prize 時用 MySQL 的值 */
    public static AdminPrizeResponse from(Prize prize, Map<Long, Integer> liveStock) {
        return new AdminPrizeResponse(
                prize.getId(),
                prize.getName(),
                prize.winProbabilityPercent(),
                prize.getTotalStock(),
                liveStock.getOrDefault(prize.getId(), prize.getRemainingStock()));
    }
}
