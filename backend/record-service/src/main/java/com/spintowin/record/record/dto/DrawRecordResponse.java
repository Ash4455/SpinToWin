package com.spintowin.record.record.dto;

import com.spintowin.record.record.entity.DrawRecord;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * 一筆 Draw Result 紀錄。欄位與抽獎回應的 results 項目相同（drawId、prizeId、prizeName、won），
 * 再加上所屬請求與抽獎時間。銘謝惠顧時 {@code won=false}，{@code prizeId} 與 {@code prizeName} 為 null。
 */
public record DrawRecordResponse(String drawId, String requestId, @Nullable Long prizeId, @Nullable String prizeName,
                                 boolean won, Instant drawnAt) {

    public static DrawRecordResponse from(DrawRecord record) {
        return new DrawRecordResponse(record.getDrawId(), record.getRequestId(), record.getPrizeId(),
                record.getPrizeName(), record.isWon(), record.getDrawnAt());
    }
}
