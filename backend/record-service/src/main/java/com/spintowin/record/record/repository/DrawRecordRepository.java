package com.spintowin.record.record.repository;

import com.spintowin.record.record.entity.DrawRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;

/** 唯讀查詢；寫入一律經 {@link DrawRecordWriter}（以 draw_id 去重）。 */
public interface DrawRecordRepository extends JpaRepository<DrawRecord, Long> {

    Page<DrawRecord> findByCampaignIdAndUserId(Long campaignId, String userId, Pageable pageable);

    /**
     * 一個 Campaign 的抽出數，依 (prizeId, prizeName) 分組；prizeId 為 null 的那組是銘謝惠顧。
     * 同一 Prize 改過名稱時會有多組，各附上該名稱最後一次被抽出的時間。
     */
    @Query("""
            select r.prizeId as prizeId, r.prizeName as prizeName, count(r) as drawnCount,
                   max(r.drawnAt) as lastDrawnAt
            from DrawRecord r
            where r.campaignId = :campaignId
            group by r.prizeId, r.prizeName
            """)
    List<PrizeNameCount> countByPrizeAndName(long campaignId);

    interface PrizeNameCount {

        @Nullable Long getPrizeId();

        @Nullable String getPrizeName();

        long getDrawnCount();

        Instant getLastDrawnAt();
    }
}
