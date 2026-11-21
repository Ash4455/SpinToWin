package com.spintowin.draw.draw;

import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.redis.LiveDrawStore;
import com.spintowin.draw.draw.model.DrawResult;
import com.spintowin.draw.draw.service.DrawService;
import com.spintowin.draw.draw.service.SecureRandomPpmSource;
import com.spintowin.draw.support.DrawTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 實際分布符合設定的 Win Probability：以正式的 {@link SecureRandomPpmSource} 抽 10 萬次（Stock 充足，不會改判
 * 銘謝惠顧），對各 Prize 與銘謝惠顧做 Pearson 卡方適合度檢定。
 * <p>
 * 5 個類別 → 自由度 4。顯著水準 α = 0.001，臨界值 χ²(0.999; 4) = 18.467（查表值，例如 NIST/SEMATECH
 * e-Handbook 1.3.6.7.4）。正確的實作每 1000 次執行才約有 1 次誤判，CI 上可視為不會 flaky；
 * 而區間邊界算錯（例如 score 存成各自的 ppm 而非累加上界）、Prize 順序錯亂或隨機數範圍錯誤，
 * 都會讓統計量遠超過臨界值。
 */
class DrawDistributionTest extends DrawTest {

    private static final int DRAWS = 100_000;
    private static final int DRAWS_PER_REQUEST = DrawService.MAX_COUNT;
    private static final double CRITICAL_VALUE_DF4_ALPHA_0_001 = 18.467;

    @Autowired
    LiveDrawStore liveDrawStore;

    @Test
    void secureRandomDrawsMatchConfiguredWinProbabilitiesByChiSquareTest() throws Exception {
        // 期望次數最小為 100（遠大於卡方近似要求的 5）
        Map<Long, Integer> ppmByPrize = new LinkedHashMap<>();
        ppmByPrize.put(1L, 1_000);    // 0.1%
        ppmByPrize.put(2L, 10_000);   // 1%
        ppmByPrize.put(3L, 95_000);   // 9.5%
        ppmByPrize.put(4L, 300_000);  // 30%
        var builder = campaigns.campaign().drawQuota(DRAWS_PER_REQUEST);
        ppmByPrize.forEach((id, ppm) -> builder.prize(id, "Prize " + id, ppm, DRAWS));
        long campaignId = builder.load();
        DrawService secureDrawService = new DrawService(liveDrawStore, new SecureRandomPpmSource());

        // 每位使用者一次 Multi-Draw 用完自己的 Draw Quota，Quota 不會成為限制
        List<DrawResult> results = drawInParallel(DRAWS / DRAWS_PER_REQUEST,
                () -> secureDrawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), DRAWS_PER_REQUEST).results());

        assertThat(results).hasSize(DRAWS);
        Map<Long, Long> observed = new LinkedHashMap<>();
        ppmByPrize.keySet().forEach(id -> observed.put(id, results.stream().filter(r -> id.equals(r.prizeId())).count()));
        long noWins = results.stream().filter(r -> !r.won()).count();

        double chiSquare = 0;
        int noWinPpm = 1_000_000;
        for (var prize : ppmByPrize.entrySet()) {
            chiSquare += term(observed.get(prize.getKey()), prize.getValue());
            noWinPpm -= prize.getValue();
        }
        chiSquare += term(noWins, noWinPpm);

        assertThat(chiSquare)
                .as("chi-square (df 4) of observed %s, no-win %d", observed, noWins)
                .isLessThan(CRITICAL_VALUE_DF4_ALPHA_0_001);
        // Stock 充足：每個中獎都扣了一個 Stock，沒有改判銘謝惠顧
        ppmByPrize.keySet().forEach(id ->
                assertThat(campaigns.stock(campaignId, id)).isEqualTo(DRAWS - observed.get(id).intValue()));
    }

    /** Pearson 卡方的一項：(O − E)² / E，E = 總次數 × 機率（ppm / 1,000,000）。 */
    private static double term(long observedCount, int ppm) {
        double expected = (double) DRAWS * ppm / 1_000_000;
        double diff = observedCount - expected;
        return diff * diff / expected;
    }

    private static List<DrawResult> drawInParallel(int requests, Callable<List<DrawResult>> request)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<List<DrawResult>>> futures = new ArrayList<>(requests);
            for (int i = 0; i < requests; i++) {
                futures.add(pool.submit(request));
            }
            List<DrawResult> results = new ArrayList<>(requests * DRAWS_PER_REQUEST);
            for (Future<List<DrawResult>> future : futures) {
                results.addAll(future.get(2, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
