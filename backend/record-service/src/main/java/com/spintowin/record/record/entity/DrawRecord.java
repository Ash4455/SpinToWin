package com.spintowin.record.record.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 一個 Draw Result 的持久化紀錄。只由 {@code DrawRecordWriter} 以去重的 SQL 寫入，JPA 端唯讀。
 * prizeId 與 prizeName 皆為 null = 銘謝惠顧。
 */
@Entity
@Immutable
@Table(name = "draw_record")
public class DrawRecord {

    @Id
    private Long id;

    @Column(name = "draw_id", nullable = false, length = 64)
    private String drawId;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    /** 在所屬請求中的順序，從 1 開始。 */
    @Column(name = "draw_seq", nullable = false)
    private int drawSeq;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "prize_id")
    private @Nullable Long prizeId;

    @Column(name = "prize_name", length = 100)
    private @Nullable String prizeName;

    @Column(name = "drawn_at", nullable = false)
    private Instant drawnAt;

    protected DrawRecord() {
    }

    public Long getId() {
        return id;
    }

    public String getDrawId() {
        return drawId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getDrawSeq() {
        return drawSeq;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public String getUserId() {
        return userId;
    }

    public @Nullable Long getPrizeId() {
        return prizeId;
    }

    public @Nullable String getPrizeName() {
        return prizeName;
    }

    public Instant getDrawnAt() {
        return drawnAt;
    }

    public boolean isWon() {
        return prizeId != null;
    }
}
