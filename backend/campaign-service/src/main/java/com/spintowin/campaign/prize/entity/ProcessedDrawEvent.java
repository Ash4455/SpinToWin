package com.spintowin.campaign.prize.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 已處理過的 draw.completed 事件（以 requestId 去重）。寫入只經
 * {@link com.spintowin.campaign.prize.repository.ProcessedDrawEventRepository#markProcessed}。
 */
@Entity
@Table(name = "processed_draw_event")
public class ProcessedDrawEvent {

    @Id
    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    protected ProcessedDrawEvent() {
    }

    public String getRequestId() {
        return requestId;
    }

    public Long getCampaignId() {
        return campaignId;
    }
}
