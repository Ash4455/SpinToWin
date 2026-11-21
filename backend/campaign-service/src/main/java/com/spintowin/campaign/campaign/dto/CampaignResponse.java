package com.spintowin.campaign.campaign.dto;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.common.probability.Ppm;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** USER 看到的 Campaign：時間窗、Draw Quota、Prize 清單與銘謝惠顧機率（百分比）。 */
public record CampaignResponse(
        Long id,
        String name,
        Instant startAt,
        Instant endAt,
        int drawQuota,
        CampaignStatus status,
        List<CampaignPrizeResponse> prizes,
        BigDecimal noWinProbability) {

    public static CampaignResponse from(Campaign campaign, List<Prize> prizes) {
        int prizePpm = prizes.stream().mapToInt(Prize::getProbabilityPpm).sum();
        return new CampaignResponse(
                campaign.getId(),
                campaign.getName(),
                campaign.getStartAt(),
                campaign.getEndAt(),
                campaign.getDrawQuota(),
                campaign.getStatus(),
                prizes.stream().map(CampaignPrizeResponse::from).toList(),
                Ppm.toPercent(Ppm.ONE_HUNDRED_PERCENT - prizePpm));
    }
}
