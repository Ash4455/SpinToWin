package com.spintowin.campaign.prize.service;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.service.CampaignActivationCheck;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;

/** 上線條件：至少一個 Prize，且機率總和 ≤ 100%（後者平時已由每次修改保證，這裡再確認一次）。 */
@Component
public class PrizeActivationCheck implements CampaignActivationCheck {

    private final PrizeRepository prizeRepository;

    public PrizeActivationCheck(PrizeRepository prizeRepository) {
        this.prizeRepository = prizeRepository;
    }

    @Override
    public void check(Campaign campaign) {
        List<Prize> prizes = prizeRepository.findByCampaignIdOrderByIdAsc(campaign.getId());
        if (prizes.isEmpty()) {
            throw new BusinessException(ErrorCode.CAMPAIGN_HAS_NO_PRIZE,
                    "Campaign " + campaign.getId() + " has no Prize; add at least one before activating.");
        }
        ProbabilitySum.requireWithinOneHundredPercent(prizes.stream().map(Prize::getProbabilityPpm).toList());
    }
}
