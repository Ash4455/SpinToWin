package com.spintowin.campaign.prize.service;

import com.spintowin.campaign.prize.entity.Prize;
import com.spintowin.campaign.prize.repository.PrizeRepository;
import com.spintowin.campaign.prize.repository.ProcessedDrawEventRepository;
import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.event.InvalidDrawEventException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

/**
 * 把 draw.completed 事件套用到 MySQL：抽中的 Prize 扣減 remaining_stock，讓它最終與 Redis（即時依據）一致。
 * <p>
 * 事件是 at-least-once：同一交易中先在已處理事件表記下 requestId，記錄失敗（已存在）就什麼都不做，
 * 因此重複投遞只扣減一次；交易 rollback 時兩者一起消失，事件重送後再完整處理一次。
 */
@Service
@Transactional(readOnly = true)
public class RemainingStockService {

    private static final Logger log = LoggerFactory.getLogger(RemainingStockService.class);

    /** 與 processed_draw_event.request_id 的長度一致（INSERT IGNORE 會截斷過長的值，因此先擋下）。 */
    private static final int MAX_REQUEST_ID_LENGTH = 64;

    private final PrizeRepository prizeRepository;
    private final ProcessedDrawEventRepository processedDrawEventRepository;

    public RemainingStockService(PrizeRepository prizeRepository,
                                 ProcessedDrawEventRepository processedDrawEventRepository) {
        this.prizeRepository = prizeRepository;
        this.processedDrawEventRepository = processedDrawEventRepository;
    }

    /**
     * 套用一筆事件。重複的事件不做任何事。
     *
     * @throws InvalidDrawEventException 事件不符合合約（不會扣減任何 Stock）
     */
    @Transactional
    public void apply(DrawCompletedEvent event) {
        requireValid(event);
        if (processedDrawEventRepository.markProcessed(event.requestId(), event.campaignId()) == 0) {
            log.debug("draw.completed {} of Campaign {} was already processed; skipped",
                    event.requestId(), event.campaignId());
            return;
        }
        for (Map.Entry<Long, Integer> won : wonCountByPrize(event).entrySet()) {
            deduct(event, won.getKey(), won.getValue());
        }
    }

    /**
     * 一般情況：一個原子的條件式 UPDATE。沒有生效時（對帳異常）改為鎖住 Prize 列再判斷：
     * 剩餘量不足就扣到 0、不屬於該 Campaign 就略過，兩者都記 ERROR 而不丟例外，
     * 讓這筆事件完成處理，不會卡住後續事件；差異留待對帳。
     */
    private void deduct(DrawCompletedEvent event, long prizeId, int count) {
        long campaignId = event.campaignId();
        if (prizeRepository.deductRemainingStock(prizeId, campaignId, count) == 1) {
            return;
        }
        Prize prize = prizeRepository.findForUpdateByIdAndCampaignId(prizeId, campaignId).orElse(null);
        if (prize == null) {
            log.error("Reconciliation anomaly: draw.completed {} won Prize {} {} time(s), but Campaign {} has no "
                    + "such Prize; nothing deducted", event.requestId(), prizeId, count, campaignId);
            return;
        }
        int remaining = prize.getRemainingStock();
        int shortfall = prize.deductDrawn(count);
        if (shortfall > 0) {
            log.error("Reconciliation anomaly: draw.completed {} won Prize {} of Campaign {} {} time(s), but MySQL "
                            + "remaining_stock was only {}; set to 0 ({} not deducted)",
                    event.requestId(), prizeId, campaignId, count, remaining, shortfall);
        }
    }

    private static void requireValid(DrawCompletedEvent event) {
        String requestId = event.requestId();
        if (requestId == null || requestId.isBlank() || requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new InvalidDrawEventException("draw.completed of Campaign " + event.campaignId()
                    + " has no usable requestId (blank or longer than " + MAX_REQUEST_ID_LENGTH + "): " + requestId);
        }
        if (event.campaignId() <= 0) {
            throw new InvalidDrawEventException("draw.completed " + requestId + " has no campaignId.");
        }
        if (event.results() == null || event.results().isEmpty() || event.results().contains(null)) {
            throw new InvalidDrawEventException("draw.completed " + requestId + " has no results.");
        }
    }

    /** prizeId → 抽中次數，依 prizeId 遞增（並行的交易以相同順序鎖 Prize 列，避免 deadlock）。 */
    private static Map<Long, Integer> wonCountByPrize(DrawCompletedEvent event) {
        Map<Long, Integer> counts = new TreeMap<>();
        for (DrawCompletedEvent.Result result : event.results()) {
            if (result.prizeId() != null) {
                counts.merge(result.prizeId(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
