package com.spintowin.record.stats.dto;

import java.util.List;

/**
 * 一個 Campaign 已落庫的抽獎統計。只含至少被抽出一次的 Prize（record-service 不知道 Campaign 的 Prize 清單），
 * 依 prizeId 遞增（即 Prize 順序）。
 *
 * @param campaignId Campaign id
 * @param totalDraws 總抽獎次數（= 各 Prize 抽出數 + 銘謝惠顧數）
 * @param noWinCount 銘謝惠顧次數
 * @param prizes     各 Prize 的抽出數
 */
public record CampaignStatsResponse(long campaignId, long totalDraws, long noWinCount, List<PrizeStats> prizes) {

    /**
     * @param prizeId    Prize id
     * @param prizeName  最近一次抽出時的 Prize 名稱（名稱改過時以最新的為準）
     * @param drawnCount 被抽出的次數
     */
    public record PrizeStats(long prizeId, String prizeName, long drawnCount) {
    }
}
