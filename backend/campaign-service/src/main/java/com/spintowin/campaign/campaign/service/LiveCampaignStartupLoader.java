package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.redis.LiveCampaign;
import com.spintowin.campaign.campaign.redis.LiveCampaignStore;
import com.spintowin.campaign.campaign.repository.CampaignRepository;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 服務啟動時，把 MySQL 中 ACTIVE、但 Redis 中沒有 live state 的 Campaign 載入 Redis（例如 seed 資料、Redis 被清空）。
 * 已在 Redis 中的 Campaign 不會被覆寫，即時的 Stock 與已用次數維持原狀。
 * <p>
 * 多個實例同時啟動也安全：每個 Campaign 在鎖住 Campaign 列的交易中，以「不存在才寫入」的單一 Lua script 載入。
 * Redis 無法連線時只記錄錯誤、不阻止服務啟動；之後可由 ADMIN 以重建操作載入。
 */
@Component
public class LiveCampaignStartupLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LiveCampaignStartupLoader.class);

    private final CampaignRepository campaignRepository;
    private final PrizeRepository prizeRepository;
    private final LiveCampaignStore store;
    private final TransactionTemplate transactionTemplate;

    public LiveCampaignStartupLoader(CampaignRepository campaignRepository, PrizeRepository prizeRepository,
                                     LiveCampaignStore store, TransactionTemplate transactionTemplate) {
        this.campaignRepository = campaignRepository;
        this.prizeRepository = prizeRepository;
        this.store = store;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            loadMissingActiveCampaigns();
        } catch (RuntimeException e) {
            log.error("Could not load ACTIVE campaigns into Redis on startup; "
                    + "rebuild them with POST /admin/campaigns/{id}/redis/rebuild.", e);
        }
    }

    /** 載入所有尚未在 Redis 中的 ACTIVE Campaign。 */
    public void loadMissingActiveCampaigns() {
        for (Campaign active : campaignRepository.findByStatus(CampaignStatus.ACTIVE)) {
            long id = active.getId();
            Boolean loaded = transactionTemplate.execute(status -> loadIfAbsent(id));
            if (Boolean.TRUE.equals(loaded)) {
                log.info("Loaded ACTIVE Campaign {} into Redis.", id);
            }
        }
    }

    private boolean loadIfAbsent(long id) {
        // 鎖住後重讀：期間可能已被結束，或 Prize 剛被修改
        Campaign campaign = campaignRepository.findByIdForUpdate(id).orElse(null);
        if (campaign == null || campaign.getStatus() != CampaignStatus.ACTIVE) {
            return false;
        }
        return store.loadIfAbsent(LiveCampaign.of(campaign, prizeRepository.findByCampaignIdOrderByIdAsc(id)));
    }
}
