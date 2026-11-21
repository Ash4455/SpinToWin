package com.spintowin.campaign.prize.service;

import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.redis.LivePrize;
import com.spintowin.campaign.campaign.repository.CampaignRepository;
import com.spintowin.campaign.campaign.service.LiveCampaignSync;
import com.spintowin.campaign.campaign.service.LiveStockReader;
import com.spintowin.campaign.prize.dto.AdminPrizeResponse;
import com.spintowin.campaign.prize.dto.PrizeCreateRequest;
import com.spintowin.campaign.prize.dto.PrizeUpdateRequest;
import com.spintowin.campaign.prize.dto.StockAdjustmentRequest;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.probability.Ppm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ADMIN 對 Prize 的設定：新增、修改、刪除與 Stock 增減。
 * <p>
 * 每個寫入操作都先鎖住所屬 Campaign 列（{@link CampaignRepository#findByIdForUpdate}），
 * 再檢查 Campaign Status 與機率總和，最後寫入 MySQL；同一 Campaign 的 Prize 修改因此彼此序列化。
 */
@Service
@Transactional(readOnly = true)
public class AdminPrizeService {

    private final CampaignRepository campaignRepository;
    private final PrizeRepository prizeRepository;
    private final LiveCampaignSync liveCampaignSync;
    private final LiveStockReader liveStockReader;

    public AdminPrizeService(CampaignRepository campaignRepository, PrizeRepository prizeRepository,
                             LiveCampaignSync liveCampaignSync, LiveStockReader liveStockReader) {
        this.campaignRepository = campaignRepository;
        this.prizeRepository = prizeRepository;
        this.liveCampaignSync = liveCampaignSync;
        this.liveStockReader = liveStockReader;
    }

    /** 新增 Prize（只限 DRAFT），新的機率總和不得超過 100%。 */
    @Transactional
    public AdminPrizeResponse create(Long campaignId, PrizeCreateRequest request) {
        Campaign campaign = lockCampaign(campaignId);
        campaign.requirePrizesRestructurable();
        int ppm = toPpm(request.winProbability());

        List<Integer> ppms = new ArrayList<>(ppmsOf(prizeRepository.findByCampaignIdOrderByIdAsc(campaignId)));
        ppms.add(ppm);
        ProbabilitySum.requireWithinOneHundredPercent(ppms);

        Prize prize = Prize.create(campaignId, request.name(), ppm, request.stock());
        return AdminPrizeResponse.from(prizeRepository.save(prize));
    }

    /**
     * 修改名稱與 Win Probability（DRAFT、ACTIVE 皆可），與其他 Prize 的機率總和不得超過 100%。
     * Campaign 為 ACTIVE 時，寫入 MySQL 後同步 Redis 中的 Prize 名稱與 ppm 區間（Stock 不變），對後續 Draw 生效。
     */
    @Transactional
    public AdminPrizeResponse update(Long campaignId, Long prizeId, PrizeUpdateRequest request) {
        Campaign campaign = lockCampaign(campaignId);
        Prize prize = findPrize(campaignId, prizeId);
        campaign.requirePrizesAdjustable();
        int ppm = toPpm(request.winProbability());

        List<Prize> prizes = prizeRepository.findByCampaignIdOrderByIdAsc(campaignId);
        List<Integer> ppms = new ArrayList<>(prizes.stream()
                .filter(other -> !other.getId().equals(prizeId))
                .map(Prize::getProbabilityPpm)
                .toList());
        ppms.add(ppm);
        ProbabilitySum.requireWithinOneHundredPercent(ppms);

        List<LivePrize> before = LivePrize.of(prizes);
        prize.update(request.name(), ppm); // prize 與 prizes 中的同一筆是同一個 managed instance
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            prizeRepository.flush();
            liveCampaignSync.prizesChanged(campaignId, before, LivePrize.of(prizes));
        }
        return AdminPrizeResponse.from(prize, liveStockReader.liveStock(campaign));
    }

    /** 刪除 Prize（只限 DRAFT）。 */
    @Transactional
    public void delete(Long campaignId, Long prizeId) {
        Campaign campaign = lockCampaign(campaignId);
        Prize prize = findPrize(campaignId, prizeId);
        campaign.requirePrizesRestructurable();
        prizeRepository.delete(prize);
    }

    /**
     * 以增減量調整 Stock（DRAFT、ACTIVE 皆可），結果不得為負數。Prize 列在交易內被鎖住。
     * <p>
     * Campaign 為 ACTIVE 時 Redis 才是 Stock 的即時依據：MySQL 寫入（並 flush）後，對 Redis 原子地套用同一 delta，
     * 以 Redis 的剩餘量判斷 STOCK_NEGATIVE（被拒絕時整筆 rollback）。因此不會覆蓋已被抽走、但 MySQL 尚未扣減的數量。
     * 回應中的 remainingStock 在 ACTIVE 時是 Redis 套用後的即時值（Campaign 不在 Redis 中時是 MySQL 的值）。
     */
    @Transactional
    public AdminPrizeResponse adjustStock(Long campaignId, Long prizeId, StockAdjustmentRequest request) {
        Campaign campaign = lockCampaign(campaignId);
        Prize prize = prizeRepository.findForUpdateByIdAndCampaignId(prizeId, campaignId)
                .orElseThrow(() -> prizeNotFound(campaignId, prizeId));
        campaign.requirePrizesAdjustable();
        prize.adjustStock(request.delta());
        if (campaign.getStatus() == CampaignStatus.ACTIVE) {
            prizeRepository.flush();
            liveCampaignSync.adjustStock(campaignId, prizeId, request.delta());
        }
        return AdminPrizeResponse.from(prize, liveStockReader.liveStock(campaign));
    }

    private Prize findPrize(Long campaignId, Long prizeId) {
        return prizeRepository.findByIdAndCampaignId(prizeId, campaignId)
                .orElseThrow(() -> prizeNotFound(campaignId, prizeId));
    }

    private static BusinessException prizeNotFound(Long campaignId, Long prizeId) {
        return new BusinessException(ErrorCode.PRIZE_NOT_FOUND,
                "Prize " + prizeId + " not found in Campaign " + campaignId + ".");
    }

    private Campaign lockCampaign(Long campaignId) {
        return campaignRepository.findByIdForUpdate(campaignId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND,
                        "Campaign " + campaignId + " not found."));
    }

    private static List<Integer> ppmsOf(List<Prize> prizes) {
        return prizes.stream().map(Prize::getProbabilityPpm).toList();
    }

    /** request 已由 Bean Validation 限制在 0 ~ 100、最多 4 位小數，這裡的換算不會失敗。 */
    private static int toPpm(BigDecimal percent) {
        return Ppm.fromPercent(percent);
    }
}
