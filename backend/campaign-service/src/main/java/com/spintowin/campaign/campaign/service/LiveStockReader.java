package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.redis.LiveCampaignStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 管理後台要顯示的剩餘 Stock：ACTIVE 的 Campaign 以 Redis（即時依據）為準，MySQL 的 remaining_stock
 * 要等 draw.completed 被消費後才追上。其他狀態、或 Campaign 不在 Redis 中（例如 Redis 資料遺失）時用 MySQL 的值。
 */
@Component
public class LiveStockReader {

    private static final Logger log = LoggerFactory.getLogger(LiveStockReader.class);

    private final LiveCampaignStore store;

    public LiveStockReader(LiveCampaignStore store) {
        this.store = store;
    }

    /**
     * @return prizeId → Redis 中的剩餘 Stock；不在其中的 Prize 應改用 MySQL 的值（空 = 全部用 MySQL）
     */
    public Map<Long, Integer> liveStock(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            return Map.of();
        }
        try {
            return store.remainingStock(campaign.getId());
        } catch (RuntimeException e) {
            log.warn("Cannot read live Stock of Campaign {} from Redis; showing MySQL remaining_stock: {}",
                    campaign.getId(), e.toString());
            return Map.of();
        }
    }
}
