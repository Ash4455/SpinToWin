package com.spintowin.campaign.campaign.service;

import com.spintowin.campaign.campaign.dto.AdminCampaignResponse;
import com.spintowin.campaign.campaign.dto.CampaignRequest;
import com.spintowin.campaign.campaign.entity.Campaign;
import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.campaign.campaign.repository.CampaignRepository;
import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** ADMIN 對 Campaign 的管理操作：建立、修改、查詢與 Campaign Status 轉換。 */
@Service
@Transactional(readOnly = true)
public class AdminCampaignService {

    private final CampaignRepository campaignRepository;
    private final PrizeRepository prizeRepository;
    private final List<CampaignActivationCheck> activationChecks;
    private final LiveCampaignSync liveCampaignSync;
    private final LiveStockReader liveStockReader;

    public AdminCampaignService(CampaignRepository campaignRepository,
                                PrizeRepository prizeRepository,
                                List<CampaignActivationCheck> activationChecks,
                                LiveCampaignSync liveCampaignSync,
                                LiveStockReader liveStockReader) {
        this.campaignRepository = campaignRepository;
        this.prizeRepository = prizeRepository;
        this.activationChecks = activationChecks;
        this.liveCampaignSync = liveCampaignSync;
        this.liveStockReader = liveStockReader;
    }

    @Transactional
    public AdminCampaignResponse create(CampaignRequest request) {
        Campaign campaign = Campaign.draft(request.name(), request.startAt(), request.endAt(), request.drawQuota());
        return AdminCampaignResponse.from(campaignRepository.save(campaign), List.of());
    }

    public AdminCampaignResponse get(Long id) {
        return toResponse(find(id));
    }

    public List<AdminCampaignResponse> list() {
        Map<Long, List<Prize>> prizesByCampaign = prizeRepository.findAll(Sort.by("id")).stream()
                .collect(Collectors.groupingBy(Prize::getCampaignId));
        return campaignRepository.findAll(Sort.by("id")).stream()
                .map(c -> AdminCampaignResponse.from(c, prizesByCampaign.getOrDefault(c.getId(), List.of()),
                        liveStockReader.liveStock(c)))
                .toList();
    }

    @Transactional
    public AdminCampaignResponse update(Long id, CampaignRequest request) {
        Campaign campaign = find(id);
        campaign.update(request.name(), request.startAt(), request.endAt(), request.drawQuota());
        return toResponse(campaign);
    }

    /**
     * DRAFT → ACTIVE：先判斷轉換是否合法，再跑所有 {@link CampaignActivationCheck}；
     * 寫入 MySQL 後把設定與 Stock 載入 Redis（之後 Redis 是 Stock 的即時依據）。
     */
    @Transactional
    public AdminCampaignResponse activate(Long id) {
        // 鎖住 Campaign 列：與 Prize 修改（同樣先鎖 Campaign）序列化，檢查時看到的 Prize 即為上線時的 Prize
        Campaign campaign = campaignRepository.findByIdForUpdate(id)
                .orElseThrow(() -> notFound(id));
        if (campaign.getStatus().canTransitionTo(CampaignStatus.ACTIVE)) {
            activationChecks.forEach(check -> check.check(campaign));
        }
        campaign.activate(); // 非法轉換在此丟出 INVALID_STATUS_TRANSITION
        campaignRepository.flush();
        List<Prize> prizes = prizeRepository.findByCampaignIdOrderByIdAsc(id);
        liveCampaignSync.activated(campaign, prizes);
        return AdminCampaignResponse.from(campaign, prizes);
    }

    /** DRAFT / ACTIVE → ENDED；已載入 Redis 的 Campaign 在 Redis 中也標為 ENDED，之後的 Draw 一律拒絕。 */
    @Transactional
    public AdminCampaignResponse end(Long id) {
        Campaign campaign = find(id);
        CampaignStatus previous = campaign.getStatus();
        campaign.end();
        campaignRepository.flush();
        liveCampaignSync.statusChanged(id, previous, campaign.getStatus());
        return toResponse(campaign);
    }

    /**
     * 從 MySQL 重建 ACTIVE Campaign 的 Redis live state：以 MySQL 的設定、Prize 與 remaining_stock 覆寫 Redis。
     * 使用者已用次數、Idempotency 結果與 outbox 不在 MySQL，維持原狀。
     * <p>
     * 用於 Redis 資料遺失之後。Redis 正常時重建會把 Stock 退回 MySQL 的值（尚未消費的 draw.completed 所扣的量會被加回），
     * 因此只應在確認 Redis 狀態有誤時使用。
     */
    @Transactional // 需要 SELECT ... FOR UPDATE，不能是 read-only 交易
    public AdminCampaignResponse rebuildLiveState(Long id) {
        // 鎖住 Campaign 列：與 Prize 修改序列化，重建期間讀到的 Prize 不會被並行修改
        Campaign campaign = campaignRepository.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (campaign.getStatus() != CampaignStatus.ACTIVE) {
            throw new BusinessException(ErrorCode.CAMPAIGN_NOT_ACTIVE,
                    "Campaign " + id + " is " + campaign.getStatus() + "; only ACTIVE campaigns have live state.");
        }
        List<Prize> prizes = prizeRepository.findByCampaignIdOrderByIdAsc(id);
        liveCampaignSync.rebuild(campaign, prizes);
        return AdminCampaignResponse.from(campaign, prizes);
    }

    /** ACTIVE 的 Campaign 顯示 Redis 的即時剩餘 Stock（見 {@link LiveStockReader}）。 */
    private AdminCampaignResponse toResponse(Campaign campaign) {
        return AdminCampaignResponse.from(campaign, prizeRepository.findByCampaignIdOrderByIdAsc(campaign.getId()),
                liveStockReader.liveStock(campaign));
    }

    private Campaign find(Long id) {
        return campaignRepository.findById(id).orElseThrow(() -> notFound(id));
    }

    private static BusinessException notFound(Long id) {
        return new BusinessException(ErrorCode.CAMPAIGN_NOT_FOUND, "Campaign " + id + " not found.");
    }
}
