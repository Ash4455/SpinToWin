package com.spintowin.common.probability;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Win Probability 的內部單位：ppm（百萬分率）。1,000,000 ppm = 100%。
 * 百分比最多 4 位小數，因此 1 ppm = 0.0001%，換算不會有誤差。
 */
public final class Ppm {

    /** 100% 對應的 ppm；所有 Prize 的 ppm 總和不得超過此值，剩餘即銘謝惠顧。 */
    public static final int ONE_HUNDRED_PERCENT = 1_000_000;

    /** 百分比的小數位數。 */
    public static final int PERCENT_SCALE = 4;

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private Ppm() {
    }

    /**
     * 百分比轉 ppm，例如 1.5 → 15000。
     *
     * @throws IllegalArgumentException 超過 4 位有效小數，或不在 0 ~ 100 之間
     */
    public static int fromPercent(BigDecimal percent) {
        BigDecimal scaled;
        try {
            scaled = percent.setScale(PERCENT_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Win Probability " + percent + "% has more than " + PERCENT_SCALE + " decimal places.", e);
        }
        if (scaled.signum() < 0 || scaled.compareTo(ONE_HUNDRED) > 0) {
            throw new IllegalArgumentException("Win Probability " + percent + "% is not between 0 and 100.");
        }
        return scaled.unscaledValue().intValueExact();
    }

    /** ppm 轉百分比，例如 10000 → 1.0000。 */
    public static BigDecimal toPercent(int ppm) {
        return BigDecimal.valueOf(ppm, PERCENT_SCALE);
    }
}
