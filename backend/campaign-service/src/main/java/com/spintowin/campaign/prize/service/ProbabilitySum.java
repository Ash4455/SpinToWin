package com.spintowin.campaign.prize.service;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.probability.Ppm;

import java.math.BigDecimal;
import java.util.Collection;

/** 一個 Campaign 所有 Prize 的 Win Probability（ppm）總和規則：總和 ≤ 100%，剩餘即銘謝惠顧。 */
public final class ProbabilitySum {

    private ProbabilitySum() {
    }

    /**
     * 確認這組 Prize ppm（新增或修改「之後」的完整集合）總和不超過 100%。
     *
     * @throws BusinessException PROBABILITY_SUM_EXCEEDED
     */
    public static void requireWithinOneHundredPercent(Collection<Integer> prizePpms) {
        long total = total(prizePpms);
        if (total > Ppm.ONE_HUNDRED_PERCENT) {
            throw new BusinessException(ErrorCode.PROBABILITY_SUM_EXCEEDED,
                    "Total Win Probability would be " + percent(total) + "%, which exceeds 100%.");
        }
    }

    /** 銘謝惠顧的 ppm：100% 減去所有 Prize 的 ppm。 */
    public static int noWinPpm(Collection<Integer> prizePpms) {
        return Math.toIntExact(Ppm.ONE_HUNDRED_PERCENT - total(prizePpms));
    }

    private static long total(Collection<Integer> prizePpms) {
        return prizePpms.stream().mapToLong(Integer::longValue).sum();
    }

    private static String percent(long ppm) {
        return BigDecimal.valueOf(ppm, Ppm.PERCENT_SCALE).toPlainString();
    }
}
