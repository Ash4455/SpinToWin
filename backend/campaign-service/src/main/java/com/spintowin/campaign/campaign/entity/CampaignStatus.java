package com.spintowin.campaign.campaign.entity;

import java.util.Set;

/** Campaign Status：DRAFT → ACTIVE → ENDED，DRAFT 也可直接 ENDED。 */
public enum CampaignStatus {
    DRAFT,
    ACTIVE,
    ENDED;

    /** 此 Status 是否允許轉換成 {@code target}。 */
    public boolean canTransitionTo(CampaignStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<CampaignStatus> allowedTargets() {
        return switch (this) {
            case DRAFT -> Set.of(ACTIVE, ENDED);
            case ACTIVE -> Set.of(ENDED);
            case ENDED -> Set.of();
        };
    }
}
