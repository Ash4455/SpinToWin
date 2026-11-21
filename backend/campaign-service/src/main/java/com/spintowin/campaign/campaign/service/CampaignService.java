package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.dto.CampaignResponse;
import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.repository.CampaignRepository;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final PrizeRepository prizeRepository;

    public CampaignService(CampaignRepository campaignRepository, PrizeRepository prizeRepository) {
        this.campaignRepository = campaignRepository;
        this.prizeRepository = prizeRepository;
    }

    /** 使用者看得到的 Campaign：DRAFT 尚未開放，與不存在的 Campaign 一樣回 CAMPAIGN_NOT_FOUND。 */
    public CampaignResponse getCampaign(Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .filter(c -> c.getStatus() != CampaignStatus.DRAFT)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign " + id + " not found."));
        return CampaignResponse.from(campaign, prizeRepository.findByCampaignIdOrderByIdAsc(id));
    }
}
