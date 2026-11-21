package com.spintowin.draw.draw;

import com.spintowin.common.error.BusinessException;
import com.spintowin.common.error.ErrorCode;
import com.spintowin.common.redis.CampaignRedisKeys;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.model.DrawResult;
import com.spintowin.draw.draw.service.DrawService;
import com.spintowin.draw.support.DrawTest;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Seam 1：{@link DrawService} + 真 Redis + 固定隨機序列。
 * 以 seed 的 Prize 組合為例：藍牙耳機 [0, 10000)、100 元購物金 [10000, 105000)、免運券 [105000, 405000)，
 * 其餘 [405000, 1000000) 為銘謝惠顧。
 */
class DrawServiceTest extends DrawTest {

    private static final long HEADPHONES = 1;
    private static final long VOUCHER = 2;
    private static final long FREE_SHIPPING = 3;

    @Autowired
    DrawService drawService;

    private long seedLikeCampaign() {
        return campaigns.campaign().drawQuota(10)
                .prize(HEADPHONES, "藍牙耳機", 10_000, 10)
                .prize(VOUCHER, "100 元購物金", 95_000, 200)
                .prize(FREE_SHIPPING, "免運券", 300_000, 1000)
                .load();
    }

    @Test
    void singleDrawAtPpmZeroWinsFirstPrizeAndDeductsStockAndQuota() {
        long campaignId = seedLikeCampaign();
        random.willReturn(0);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        assertThat(result.results()).extracting(DrawResult::prizeId, DrawResult::prizeName)
                .containsExactly(tuple(HEADPHONES, "藍牙耳機"));
        assertThat(result.remainingDraws()).isEqualTo(9);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(9);
    }

    @ParameterizedTest(name = "ppm {0} -> {1}")
    @CsvSource(nullValues = "NO_WIN", value = {
            "0,      藍牙耳機",
            "9999,   藍牙耳機",
            "10000,  100 元購物金",
            "104999, 100 元購物金",
            "105000, 免運券",
            "404999, 免運券",
            "405000, NO_WIN",
            "999999, NO_WIN"})
    void ppmLandingPointPicksPrizeByCumulativeIntervalsInPrizeOrder(int ppm, String expectedPrize) {
        long campaignId = seedLikeCampaign();
        random.willReturn(ppm);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        assertThat(result.results()).singleElement().satisfies(r -> {
            assertThat(r.prizeName()).isEqualTo(expectedPrize);
            assertThat(r.won()).isEqualTo(expectedPrize != null);
        });
    }

    @Test
    void noWinDeductsQuotaButNoStock() {
        long campaignId = seedLikeCampaign();
        random.willReturn(999_999);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        assertThat(result.results()).singleElement().satisfies(r -> {
            assertThat(r.won()).isFalse();
            assertThat(r.prizeId()).isNull();
        });
        assertThat(result.remainingDraws()).isEqualTo(9);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(10);
        assertThat(campaigns.stock(campaignId, VOUCHER)).isEqualTo(200);
        assertThat(campaigns.stock(campaignId, FREE_SHIPPING)).isEqualTo(1000);
    }

    @Test
    void prizeWithZeroStockIsJudgedNoWinAndStockStaysZero() {
        long campaignId = campaigns.campaign()
                .prize(HEADPHONES, "藍牙耳機", 10_000, 0)
                .prize(VOUCHER, "100 元購物金", 95_000, 200)
                .load();
        random.willReturn(5_000);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1);

        assertThat(result.results()).singleElement().satisfies(r -> assertThat(r.won()).isFalse());
        assertThat(result.remainingDraws()).isEqualTo(9);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isZero();
    }

    @Test
    void multiDrawJudgesEachDrawIndependentlyAndCanWinTheSamePrizeRepeatedly() {
        long campaignId = seedLikeCampaign();
        random.willReturn(0, 9_999, 500_000, 10_000, 200_000);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 5);

        assertThat(result.results()).extracting(DrawResult::prizeId)
                .containsExactly(HEADPHONES, HEADPHONES, null, VOUCHER, FREE_SHIPPING);
        assertThat(result.remainingDraws()).isEqualTo(5);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(8);
        assertThat(campaigns.stock(campaignId, VOUCHER)).isEqualTo(199);
        assertThat(campaigns.stock(campaignId, FREE_SHIPPING)).isEqualTo(999);
    }

    @Test
    void multiDrawRunsOutOfStockMidwayAndLaterDrawsOfThatPrizeAreNoWin() {
        long campaignId = campaigns.campaign()
                .prize(HEADPHONES, "藍牙耳機", 1_000_000, 2)
                .load();
        random.willReturn(1, 2, 3, 4);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 4);

        assertThat(result.results()).extracting(DrawResult::prizeId)
                .containsExactly(HEADPHONES, HEADPHONES, null, null);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isZero();
    }

    @Test
    void everyDrawGetsItsOwnDrawId() {
        long campaignId = seedLikeCampaign();
        random.willReturn(1, 2, 3);

        DrawRequestResult result = drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 3);

        assertThat(result.results()).extracting(DrawResult::drawId).doesNotHaveDuplicates().doesNotContainNull();
    }

    @Test
    void multiDrawLargerThanRemainingQuotaIsRejectedWholeWithoutDeductingAnything() {
        long campaignId = campaigns.campaign().drawQuota(3)
                .prize(HEADPHONES, "藍牙耳機", 1_000_000, 10)
                .load();
        String userId = newUserId();
        random.willReturn(1, 2);
        drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 2);

        random.willReturn(3, 4);
        assertThatThrownBy(() -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));

        assertThat(drawService.quota(campaignId, userId).remainingDraws()).isEqualTo(1);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(8);
    }

    @Test
    void drawWithExhaustedQuotaIsRejected() {
        long campaignId = campaigns.campaign().drawQuota(1).prize(HEADPHONES, "藍牙耳機", 10_000, 10).load();
        String userId = newUserId();
        random.willReturn(500_000, 0);
        drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1);

        assertThatThrownBy(() -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.QUOTA_EXCEEDED));
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(10);
    }

    @Test
    void quotaIsPerUser() {
        long campaignId = campaigns.campaign().drawQuota(2).load();
        String alice = newUserId();
        random.willReturn(1, 2);
        drawService.draw(new IdempotencyScope(campaignId, alice, newIdempotencyKey()), 2);

        assertThat(drawService.quota(campaignId, alice).remainingDraws()).isZero();
        assertThat(drawService.quota(campaignId, newUserId()).remainingDraws()).isEqualTo(2);
    }

    @Test
    void quotaReportsDrawQuotaUsedAndRemaining() {
        long campaignId = campaigns.campaign().drawQuota(10).load();
        String userId = newUserId();
        random.willReturn(1, 2, 3);
        drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 3);

        var quota = drawService.quota(campaignId, userId);

        assertThat(quota.drawQuota()).isEqualTo(10);
        assertThat(quota.usedDraws()).isEqualTo(3);
        assertThat(quota.remainingDraws()).isEqualTo(7);
    }

    @Test
    void campaignThatIsNotActiveIsRejectedWithoutDeductingAnything() {
        long campaignId = campaigns.campaign().prize(HEADPHONES, "藍牙耳機", 1_000_000, 10).load();
        campaigns.end(campaignId);
        String userId = newUserId();
        random.willReturn(0);

        assertNotActive(() -> drawService.draw(new IdempotencyScope(campaignId, userId, newIdempotencyKey()), 1));

        assertThat(drawService.quota(campaignId, userId).usedDraws()).isZero();
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(10);
    }

    @Test
    void campaignNotLoadedIntoRedisIsRejectedAsNotActive() {
        random.willReturn(0);

        assertNotActive(() -> drawService.draw(new IdempotencyScope(987_654_321L, newUserId(), newIdempotencyKey()), 1));
        assertNotActive(() -> drawService.quota(987_654_321L, newUserId()));
    }

    @Test
    void activeCampaignBeforeStartAtIsRejected() {
        Instant now = Instant.now();
        long campaignId = campaigns.campaign()
                .window(now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(1)))
                .prize(HEADPHONES, "藍牙耳機", 1_000_000, 10).load();
        random.willReturn(0);

        assertNotActive(() -> drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1));
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(10);
    }

    @Test
    void activeCampaignAfterEndAtIsRejected() {
        Instant now = Instant.now();
        long campaignId = campaigns.campaign()
                .window(now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(1)))
                .prize(HEADPHONES, "藍牙耳機", 1_000_000, 10).load();
        random.willReturn(0);

        assertNotActive(() -> drawService.draw(new IdempotencyScope(campaignId, newUserId(), newIdempotencyKey()), 1));
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(10);
    }

    @Test
    void retryWithSameIdempotencyKeyReturnsTheSameResultAndDeductsOnlyOnce() {
        long campaignId = seedLikeCampaign();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(0, 500_000, 20_000);
        DrawRequestResult first = drawService.draw(new IdempotencyScope(campaignId, userId, key), 3);

        // 重送時 Java 端仍會產生新的落點，但 Lua 直接回傳先前的結果
        random.willReturn(999_999, 999_999, 999_999);
        DrawRequestResult retried = drawService.draw(new IdempotencyScope(campaignId, userId, key), 3);

        assertThat(retried).isEqualTo(first);
        assertThat(retried.results()).extracting(DrawResult::prizeId).containsExactly(HEADPHONES, null, VOUCHER);
        assertThat(drawService.quota(campaignId, userId).usedDraws()).isEqualTo(3);
        assertThat(campaigns.stock(campaignId, HEADPHONES)).isEqualTo(9);
        assertThat(campaigns.stock(campaignId, VOUCHER)).isEqualTo(199);
    }

    @Test
    void retryIsAnsweredFromThePreviousResultEvenAfterTheCampaignEnded() {
        long campaignId = seedLikeCampaign();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(0);
        DrawRequestResult first = drawService.draw(new IdempotencyScope(campaignId, userId, key), 1);
        campaigns.end(campaignId);

        random.willReturn(0);
        assertThat(drawService.draw(new IdempotencyScope(campaignId, userId, key), 1)).isEqualTo(first);
    }

    @Test
    void retryWithSameKeyIsStoredFor24Hours() {
        long campaignId = seedLikeCampaign();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(0);
        drawService.draw(new IdempotencyScope(campaignId, userId, key), 1);

        Long ttl = redis.getExpire(CampaignRedisKeys.idempotency(new IdempotencyScope(campaignId, userId, key)));
        assertThat(ttl).isBetween(Duration.ofHours(24).toSeconds() - 60, Duration.ofHours(24).toSeconds());
    }

    @Test
    void sameIdempotencyKeyFromAnotherUserIsAnIndependentDraw() {
        long campaignId = seedLikeCampaign();
        String key = newIdempotencyKey();
        random.willReturn(0, 999_999);

        DrawRequestResult alice = drawService.draw(new IdempotencyScope(campaignId, newUserId(), key), 1);
        DrawRequestResult bob = drawService.draw(new IdempotencyScope(campaignId, newUserId(), key), 1);

        assertThat(alice.results()).extracting(DrawResult::prizeId).containsExactly(HEADPHONES);
        assertThat(bob.results()).extracting(DrawResult::prizeId).containsOnlyNulls();
        assertThat(bob.requestId()).isNotEqualTo(alice.requestId());
    }

    @Test
    void rejectedRequestIsNotRememberedSoTheSameKeyCanSucceedLater() {
        Instant now = Instant.now();
        long campaignId = campaigns.campaign()
                .window(now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(1)))
                .drawQuota(1)
                .prize(HEADPHONES, "藍牙耳機", 1_000_000, 10).load();
        String userId = newUserId();
        String key = newIdempotencyKey();
        random.willReturn(0, 0);
        assertThatThrownBy(() -> drawService.draw(new IdempotencyScope(campaignId, userId, key), 2))
                .isInstanceOf(BusinessException.class);

        random.willReturn(0);
        assertThat(drawService.draw(new IdempotencyScope(campaignId, userId, key), 1).results()).hasSize(1);
    }

    private static void assertNotActive(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CAMPAIGN_NOT_ACTIVE));
    }
}
