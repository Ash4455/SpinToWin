package com.spintowin.campaign.campaign.dto;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.prize.dto.AdminPrizeResponse;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.campaign.prize.service.ProbabilitySum;
import com.spintowin.common.probability.Ppm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** ADMIN 看到的 Campaign 設定、Campaign Status、Prize（依 id 排序）與銘謝惠顧機率（百分比）。 */
public record AdminCampaignResponse(
        Long id,
        String name,
        Instant startAt,
        Instant endAt,
        int drawQuota,
        CampaignStatus status,
        List<AdminPrizeResponse> prizes,
        BigDecimal noWinProbability) {

    /** {@code prizes} 須為此 Campaign 的 Prize，依 id 排序；remainingStock 用 MySQL 的值。 */
    public static AdminCampaignResponse from(Campaign campaign, List<Prize> prizes) {
        return from(campaign, prizes, Map.of());
    }

    /**
     * @param prizes    此 Campaign 的 Prize，依 id 排序
     * @param liveStock prizeId → 即時剩餘 Stock（Redis）；沒有的 Prize 用 MySQL 的值
     */
    public static AdminCampaignResponse from(Campaign campaign, List<Prize> prizes, Map<Long, Integer> liveStock) {
        return new AdminCampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getStartAt(),
                campaign.getEndAt(),
                campaign.getDrawQuota(),
                campaign.getStatus(),
                prizes.stream().map(prize -> AdminPrizeResponse.from(prize, liveStock)).toList(),
                Ppm.toPercent(ProbabilitySum.noWinPpm(prizes.stream().map(Prize::getProbabilityPpm).toList())));
    }
}
