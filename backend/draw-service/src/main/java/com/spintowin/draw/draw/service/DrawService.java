package com.spintowin.draw.draw.service;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.model.DrawQuotaStatus;
import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.redis.DrawAttempt;
import com.spintowin.draw.draw.redis.LiveDrawStore;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 抽獎（單抽與 Multi-Draw）：Java 端為每一抽產生隨機落點，判定與扣減全部交給 Redis 中的單一 Lua script。
 */
@Service
public class DrawService {

    /** Multi-Draw 一次最多幾抽。 */
    public static final int MAX_COUNT = 10;

    private final LiveDrawStore store;
    private final RandomPpmSource random;

    public DrawService(LiveDrawStore store, RandomPpmSource random) {
        this.store = store;
        this.random = random;
    }

    /**
     * 進行 {@code count} 次 Draw，每一抽獨立判定。以相同的 {@code scope}（Campaign、使用者、Idempotency-Key）
     * 重送時回傳先前的結果，不再扣減。
     *
     * @throws BusinessException QUOTA_EXCEEDED：剩餘次數不足 count（整筆拒絕）
     */
    public DrawRequestResult draw(IdempotencyScope scope, int count) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT + ": " + count);
        }
        int[] ppms = new int[count];
        for (int i = 0; i < count; i++) {
            ppms[i] = random.nextPpm();
        }
        String requestId = UUID.randomUUID().toString();
        DrawAttempt attempt = store.draw(scope, requestId, ppms);
        return switch (attempt) {
            case DrawAttempt.Drawn drawn -> drawn.result();
            case DrawAttempt.QuotaExceeded exceeded -> throw new BusinessException(ErrorCode.QUOTA_EXCEEDED,
                    "Requested " + count + " draws but only " + exceeded.remainingDraws() + " remain.");
            case DrawAttempt.NotActive notActive -> throw new BusinessException(ErrorCode.CAMPAIGN_NOT_ACTIVE,
                    notActiveDetail(scope.campaignId(), notActive.reason()));
        };
    }

    /** 使用者在此 Campaign 的 Draw Quota 使用狀況。 */
    public DrawQuotaStatus quota(long campaignId, String userId) {
        return store.quota(campaignId, userId).orElseThrow(() -> notActive(campaignId));
    }

    private static BusinessException notActive(long campaignId) {
        return new BusinessException(ErrorCode.CAMPAIGN_NOT_ACTIVE,
                notActiveDetail(campaignId, DrawAttempt.NotActive.Reason.NOT_LOADED));
    }

    private static String notActiveDetail(long campaignId, DrawAttempt.NotActive.Reason reason) {
        return switch (reason) {
            // draw-service 只看得到 Redis：DRAFT 或不存在的 Campaign 都還沒被載入
            case NOT_LOADED, NOT_ACTIVE -> "Campaign " + campaignId + " is not active.";
            case NOT_STARTED -> "Campaign " + campaignId + " has not started yet.";
            case ENDED -> "Campaign " + campaignId + " has ended.";
        };
    }
}
