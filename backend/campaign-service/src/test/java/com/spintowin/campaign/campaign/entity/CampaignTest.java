package com.spintowin.campaign.campaign.entity;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Campaign Status 轉換規則：只允許 DRAFT → ACTIVE → ENDED，以及 DRAFT → ENDED。 */
class CampaignTest {

    private static final Instant START = Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2027-01-31T23:59:59Z");

    @Test
    void newCampaignIsDraft() {
        assertThat(draft().getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void draftCanBeActivated() {
        Campaign campaign = draft();

        campaign.activate();

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    void activeCanBeEnded() {
        Campaign campaign = active();

        campaign.end();

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
    }

    @Test
    void draftCanBeEndedDirectly() {
        Campaign campaign = draft();

        campaign.end();

        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
    }

    @Test
    void activeCannotBeActivatedAgain() {
        Campaign campaign = active();

        assertInvalidTransition(campaign::activate);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ACTIVE);
    }

    @Test
    void endedCannotBeActivated() {
        Campaign campaign = ended();

        assertInvalidTransition(campaign::activate);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
    }

    @Test
    void endedCannotBeEndedAgain() {
        Campaign campaign = ended();

        assertInvalidTransition(campaign::end);
        assertThat(campaign.getStatus()).isEqualTo(CampaignStatus.ENDED);
    }

    @Test
    void draftCanBeEdited() {
        Campaign campaign = draft();
        Instant newStart = Instant.parse("2027-02-01T00:00:00Z");
        Instant newEnd = Instant.parse("2027-02-28T00:00:00Z");

        campaign.update("新名稱", newStart, newEnd, 5);

        assertThat(campaign.getName()).isEqualTo("新名稱");
        assertThat(campaign.getStartAt()).isEqualTo(newStart);
        assertThat(campaign.getEndAt()).isEqualTo(newEnd);
        assertThat(campaign.getDrawQuota()).isEqualTo(5);
    }

    @Test
    void activeCannotBeEdited() {
        Campaign campaign = active();

        assertThatThrownBy(() -> campaign.update("新名稱", START, END, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE));
        assertThat(campaign.getName()).isEqualTo("春節轉盤");
    }

    @Test
    void endedCannotBeEdited() {
        Campaign campaign = ended();

        assertThatThrownBy(() -> campaign.update("新名稱", START, END, 5))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CAMPAIGN_NOT_EDITABLE));
    }

    private static void assertInvalidTransition(Runnable transition) {
        assertThatThrownBy(transition::run)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_STATUS_TRANSITION));
    }

    private static Campaign draft() {
        return Campaign.draft("春節轉盤", START, END, 3);
    }

    private static Campaign active() {
        Campaign campaign = draft();
        campaign.activate();
        return campaign;
    }

    private static Campaign ended() {
        Campaign campaign = active();
        campaign.end();
        return campaign;
    }
}
