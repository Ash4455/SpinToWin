package com.spintowin.campaign.campaign.dto;

import com.spintowin.campaign.prize.entity.Prize;

import java.math.BigDecimal;

/** 轉盤上的一格 Prize：名稱與 Win Probability（百分比）。 */
public record CampaignPrizeResponse(Long id, String name, BigDecimal winProbability) {

    public static CampaignPrizeResponse from(Prize prize) {
        return new CampaignPrizeResponse(prize.getId(), prize.getName(), prize.winProbabilityPercent());
    }
}
