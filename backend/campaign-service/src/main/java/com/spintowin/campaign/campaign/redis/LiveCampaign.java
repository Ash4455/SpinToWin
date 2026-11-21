package com.spintowin.campaign.campaign.redis;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.prize.entity.Prize;

import java.time.Instant;
import java.util.List;

/** 一個 Campaign 在 Redis live state 中的完整內容（快照）；{@code prizes} 依 Prize 順序排列。 */
public record LiveCampaign(long id, CampaignStatus status, Instant startAt, Instant endAt, int drawQuota,
                           List<LivePrize> prizes) {

    /** @param prizes 依 Prize 順序（id 遞增）排列 */
    public static LiveCampaign of(Campaign campaign, List<Prize> prizes) {
        return new LiveCampaign(campaign.getId(), campaign.getStatus(), campaign.getStartAt(), campaign.getEndAt(),
                campaign.getDrawQuota(), LivePrize.of(prizes));
    }
}
