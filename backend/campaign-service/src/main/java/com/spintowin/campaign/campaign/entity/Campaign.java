package com.spintowin.campaign.campaign.entity;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "campaign")
public class Campaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "draw_quota", nullable = false)
    private int drawQuota;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status;

    protected Campaign() {
    }

    private Campaign(String name, Instant startAt, Instant endAt, int drawQuota, CampaignStatus status) {
        this.name = name;
        this.startAt = startAt;
        this.endAt = endAt;
        this.drawQuota = drawQuota;
        this.status = status;
    }

    /** 建立一個 DRAFT Campaign。欄位合法性（時間窗、Draw Quota）由 request 驗證與 DB constraint 保證。 */
    public static Campaign draft(String name, Instant startAt, Instant endAt, int drawQuota) {
        return new Campaign(name, startAt, endAt, drawQuota, CampaignStatus.DRAFT);
    }

    /** 修改設定；只有 DRAFT 可改。 */
    public void update(String name, Instant startAt, Instant endAt, int drawQuota) {
        if (status != CampaignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CAMPAIGN_NOT_EDITABLE,
                    "Campaign " + id + " is " + status + "; only DRAFT campaigns can be edited.");
        }
        this.name = name;
        this.startAt = startAt;
        this.endAt = endAt;
        this.drawQuota = drawQuota;
    }

    /** 新增或刪除 Prize（改變 Prize 組成）：只有 DRAFT 可以。 */
    public void requirePrizesRestructurable() {
        if (status != CampaignStatus.DRAFT) {
            throw new BusinessException(ErrorCode.CAMPAIGN_NOT_EDITABLE,
                    "Campaign " + id + " is " + status + "; Prizes can only be added or deleted while DRAFT.");
        }
    }

    /** 修改 Prize 名稱、Win Probability 或增減 Stock：DRAFT 與 ACTIVE 可以，ENDED 不行。 */
    public void requirePrizesAdjustable() {
        if (status == CampaignStatus.ENDED) {
            throw new BusinessException(ErrorCode.CAMPAIGN_NOT_EDITABLE,
                    "Campaign " + id + " is ENDED; its Prizes can no longer be changed.");
        }
    }

    /** 上線：DRAFT → ACTIVE。上線前的其他前置條件由 {@code CampaignActivationCheck} 檢查。 */
    public void activate() {
        transitionTo(CampaignStatus.ACTIVE);
    }

    /** 結束：DRAFT / ACTIVE → ENDED。 */
    public void end() {
        transitionTo(CampaignStatus.ENDED);
    }

    private void transitionTo(CampaignStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Campaign " + id + " cannot transition from " + status + " to " + target + ".");
        }
        this.status = target;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public int getDrawQuota() {
        return drawQuota;
    }

    public CampaignStatus getStatus() {
        return status;
    }
}
