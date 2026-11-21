package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.redis.LiveCampaign;
import com.spintowin.campaign.campaign.redis.LiveCampaignStore;
import com.spintowin.campaign.campaign.redis.LivePrize;
import com.spintowin.campaign.campaign.redis.StockAdjustment;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 在 MySQL 交易中同步 Redis live state。
 * <p>
 * 順序：呼叫端先完成 MySQL 的驗證與寫入（並 flush），最後才呼叫這裡寫 Redis；
 * 若之後交易 rollback（例如 commit 失敗），以登記好的補償把 Redis 改回原狀。
 * 補償本身失敗時只記 log，需由 ADMIN 以重建操作從 MySQL 修復。
 */
@Component
public class LiveCampaignSync {

    private static final Logger log = LoggerFactory.getLogger(LiveCampaignSync.class);

    private final LiveCampaignStore store;

    public LiveCampaignSync(LiveCampaignStore store) {
        this.store = store;
    }

    /** Campaign 剛上線：載入設定與 Stock；rollback 時移除。 */
    public void activated(Campaign campaign, List<Prize> prizes) {
        long id = campaign.getId();
        store.replace(LiveCampaign.of(campaign, prizes));
        onRollback("unload Campaign " + id, () -> store.unload(id));
    }

    /** 以 MySQL 的內容覆寫 live state（沒有補償：重建本身不改 MySQL）。 */
    public void rebuild(Campaign campaign, List<Prize> prizes) {
        store.replace(LiveCampaign.of(campaign, prizes));
        log.info("Rebuilt Redis live state of Campaign {} from MySQL.", campaign.getId());
    }

    /** Campaign Status 改變（例如結束）：更新已載入 Redis 的 Status；rollback 時改回原本的 Status。 */
    public void statusChanged(long campaignId, CampaignStatus previous, CampaignStatus current) {
        if (store.setStatus(campaignId, current)) {
            onRollback("restore status of Campaign " + campaignId, () -> store.setStatus(campaignId, previous));
        }
    }

    /**
     * 上線期間修改了 Prize 名稱或 Win Probability：重寫 Redis 中的 ppm 區間與名稱；rollback 時寫回修改前的值。
     *
     * @param before 修改前、依 Prize 順序排列的完整清單
     * @param after  修改後、依 Prize 順序排列的完整清單
     */
    public void prizesChanged(long campaignId, List<LivePrize> before, List<LivePrize> after) {
        if (store.syncPrizes(campaignId, after)) {
            onRollback("restore Prizes of Campaign " + campaignId, () -> store.syncPrizes(campaignId, before));
        } else {
            log.warn("Campaign {} is ACTIVE but not loaded in Redis; the Prize change is in MySQL only. "
                    + "Rebuild the Campaign to load it.", campaignId);
        }
    }

    /**
     * 上線期間的 Stock 增減：原子地套用到 Redis 的剩餘 Stock；rollback 時套用反向的 delta。
     *
     * @throws BusinessException STOCK_NEGATIVE：Redis 的剩餘 Stock 加上 delta 會是負數（Redis 未改變）
     */
    public void adjustStock(long campaignId, long prizeId, int delta) {
        StockAdjustment result = store.adjustStock(campaignId, prizeId, delta);
        switch (result.outcome()) {
            case APPLIED -> onRollback("revert Stock delta " + delta + " of Prize " + prizeId,
                    () -> revertStock(campaignId, prizeId, delta));
            case WOULD_BE_NEGATIVE -> throw new BusinessException(ErrorCode.STOCK_NEGATIVE,
                    "Prize " + prizeId + " has " + result.remaining() + " remaining; a delta of " + delta
                            + " would make Stock negative.");
            case NOT_LOADED -> log.warn("Campaign {} is ACTIVE but not loaded in Redis; the Stock delta is in "
                    + "MySQL only. Rebuild the Campaign to load it.", campaignId);
        }
    }

    private void revertStock(long campaignId, long prizeId, int delta) {
        StockAdjustment reverted = store.adjustStock(campaignId, prizeId, -delta);
        if (reverted.outcome() != StockAdjustment.Outcome.APPLIED) {
            // 加上去的 Stock 已被抽走：Redis 的數量是真實發生的抽獎，保留它，只提醒對帳
            log.error("Could not revert Stock delta {} of Prize {} in Campaign {} ({}, remaining {}); "
                            + "Redis and MySQL Stock differ until reconciled.",
                    delta, prizeId, campaignId, reverted.outcome(), reverted.remaining());
        }
    }

    private static void onRollback(String description, Runnable compensation) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                if (status == STATUS_UNKNOWN) {
                    log.error("Transaction outcome unknown; Redis live state may disagree with MySQL "
                            + "(pending compensation: {}). Rebuild the Campaign from MySQL.", description);
                    return;
                }
                try {
                    compensation.run();
                } catch (RuntimeException e) {
                    log.error("Compensation '{}' failed; rebuild the Campaign from MySQL.", description, e);
                }
            }
        });
    }
}
