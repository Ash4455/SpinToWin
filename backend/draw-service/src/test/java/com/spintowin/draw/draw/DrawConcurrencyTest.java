package com.spintowin.draw.draw;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.model.DrawResult;
import com.spintowin.draw.draw.service.DrawService;
import com.spintowin.draw.support.DrawTest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seam 1 的併發保證：多個執行緒同時打 {@link DrawService}（真 Redis），檢查「絕不超抽、絕不超次數、同一個
 * Idempotency-Key 只扣一次」。所有執行緒先在同一道閘門前等待，再一起放行，讓請求盡量同時抵達 Redis。
 * <p>
 * 這些斷言之所以有意義：若「檢查 Stock / 已用次數 / Idempotency 結果」與「扣減 / 寫入」拆成兩次 Redis 往返
 * （例如 Java 端先 GET 再 DECR），同時放行的執行緒會在扣減前都讀到同一個舊值，抽出的數量、成功次數或扣減次數
 * 就會超過上限；只有整段判定在單一 Lua script 中原子執行時，這些斷言才會成立。
 */
class DrawConcurrencyTest extends DrawTest {

    private static final long PRIZE = 1;
    private static final int ALWAYS = 1_000_000;

    @Autowired
    DrawService drawService;

    @Test
    void thousandConcurrentDrawsOnStockTenAwardExactlyTenPrizesAndTheRestNoWin() {
        long campaignId = campaigns.campaign().prize(PRIZE, "限量公仔", ALWAYS, 10).load();
        int threads = 1_000;
        random.willReturn(new int[threads]); // 全部落在 ppm 0：機率 100% 的 Prize

        List<Outcome> outcomes = runConcurrently(threads,
                i -> drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.rejection()).isNull());
        List<DrawResult> results = outcomes.stream().flatMap(o -> o.result().results().stream()).toList();
        assertThat(results).hasSize(threads);
        assertThat(results).filteredOn(DrawResult::won).hasSize(10)
                .allSatisfy(r -> assertThat(r.prizeId()).isEqualTo(PRIZE));
        assertThat(results).filteredOn(r -> !r.won()).hasSize(990);
        assertThat(campaigns.stock(campaignId, PRIZE)).isZero();
    }

    @Test
    void concurrentSingleDrawsBySameUserNeverExceedDrawQuota() {
        long campaignId = campaigns.campaign().drawQuota(10).prize(PRIZE, "免運券", ALWAYS, 1_000).load();
        String userId = newUserId();
        int threads = 200;
        random.willReturn(new int[threads]);

        List<Outcome> outcomes = runConcurrently(threads,
                i -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1));

        assertThat(outcomes).filteredOn(o -> o.result() != null).hasSize(10);
        assertThat(outcomes).filteredOn(o -> o.result() == null).hasSize(190)
                .allSatisfy(o -> assertThat(o.rejection().getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
        assertThat(drawService.quota(campaignId, userId).usedDraws()).isEqualTo(10);
        assertThat(campaigns.stock(campaignId, PRIZE)).isEqualTo(1_000 - 10);
    }

    @Test
    void concurrentMultiDrawsBySameUserAreAcceptedOrRejectedAsAWholeWithinDrawQuota() {
        long campaignId = campaigns.campaign().drawQuota(10).prize(PRIZE, "免運券", ALWAYS, 1_000).load();
        String userId = newUserId();
        int threads = 100;
        random.willReturn(new int[threads * 3]);

        List<Outcome> outcomes = runConcurrently(threads,
                i -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 3));

        // 10 次只夠 3 個三連抽；剩下的 1 次不足 3，整筆拒絕，不會部分成功
        assertThat(outcomes).filteredOn(o -> o.result() != null).hasSize(3)
                .allSatisfy(o -> assertThat(o.result().results()).hasSize(3));
        assertThat(outcomes).filteredOn(o -> o.result() == null).hasSize(97)
                .allSatisfy(o -> assertThat(o.rejection().getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
        assertThat(drawService.quota(campaignId, userId).usedDraws()).isEqualTo(9);
        assertThat(campaigns.stock(campaignId, PRIZE)).isEqualTo(1_000 - 9);
    }

    @Test
    void concurrentRetriesWithSameIdempotencyKeyDeductOnceAndAllGetTheSameResult() {
        long campaignId = campaigns.campaign().drawQuota(10).prize(PRIZE, "免運券", ALWAYS, 1_000).load();
        String userId = newUserId();
        String idempotencyKey = newIdempotencyKey();
        int threads = 200;
        random.willReturn(new int[threads * 3]); // DrawService 每次呼叫都先取落點，重送也一樣

        List<Outcome> outcomes = runConcurrently(threads,
                i -> drawService.draw(new IdempotencyScope(campaignId, userId, idempotencyKey), 3));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.rejection()).isNull());
        DrawRequestResult first = outcomes.getFirst().result();
        assertThat(outcomes).extracting(Outcome::result).containsOnly(first);
        assertThat(first.results()).hasSize(3);
        assertThat(drawService.quota(campaignId, userId).usedDraws()).isEqualTo(3);
        assertThat(campaigns.stock(campaignId, PRIZE)).isEqualTo(1_000 - 3);
    }

    /** 一次呼叫的結果：成功的 {@link DrawRequestResult}，或被拒絕的 {@link BusinessException}。 */
    private record Outcome(@Nullable DrawRequestResult result, @Nullable BusinessException rejection) {
    }

    /** 以 {@code threads} 個執行緒、同一道起跑閘門同時執行 {@code task}，依序回傳每個執行緒的結果。 */
    private static List<Outcome> runConcurrently(int threads, IntFunction<DrawRequestResult> task) {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Outcome>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        return new Outcome(task.apply(index), null);
                    } catch (BusinessException e) {
                        return new Outcome(null, e);
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("all threads ready").isTrue();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>(threads);
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } catch (Exception e) {
            throw new AssertionError("concurrent draws did not complete", e);
        } finally {
            pool.shutdownNow();
        }
    }
}
