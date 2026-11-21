package com.spintowin.draw.support;

import com.spintowin.common.redis.CampaignRedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 以 campaign-service 的角度把 Campaign 的 live state 寫進 Redis（key 規格見 {@link CampaignRedisKeys}），
 * 讓 draw-service 的測試不需要 MySQL 與 campaign-service。
 * <p>
 * 每次 {@link Builder#load()} 都配發新的 campaignId，測試之間共用同一個 Redis 也不會互相干擾。
 * 預設：ACTIVE、時間窗為現在前後一天、Draw Quota 10、沒有 Prize；並像 campaign-service 一樣把 campaignId
 * 加進全域索引 {@link CampaignRedisKeys#LIVE_CAMPAIGNS}（outbox relay 據此找到 outbox stream）。
 */
public class LiveCampaignFixture {

    private static final AtomicLong NEXT_ID = new AtomicLong(1_000);

    private final StringRedisTemplate redis;

    public LiveCampaignFixture(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Builder campaign() {
        return new Builder();
    }

    /** 讀 Redis 中的剩餘 Stock（campaign-service 與 draw-service 共用的 live state 合約）。 */
    public int stock(long campaignId, long prizeId) {
        Object value = redis.opsForHash().get(CampaignRedisKeys.stock(campaignId), String.valueOf(prizeId));
        return Integer.parseInt((String) value);
    }

    /** 把 Campaign 加進 {@link CampaignRedisKeys#LIVE_CAMPAIGNS}（搭配 {@link Builder#unlisted()}）。 */
    public void list(long campaignId) {
        redis.opsForSet().add(CampaignRedisKeys.LIVE_CAMPAIGNS, String.valueOf(campaignId));
    }

    /** 模擬 ADMIN 結束活動（campaign-service 只改 meta 的 status）。 */
    public void end(long campaignId) {
        redis.opsForHash().put(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.META_STATUS, "ENDED");
    }

    /** campaign-service 結束 Campaign 的交易 rollback 時，把 Redis 的狀態補償回 ACTIVE。 */
    public void reactivate(long campaignId) {
        redis.opsForHash().put(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.META_STATUS, "ACTIVE");
    }

    private record PrizeSpec(long id, String name, int ppm, int stock) {
    }

    public final class Builder {

        private String status = "ACTIVE";
        private Instant startAt = Instant.now().minus(Duration.ofDays(1));
        private Instant endAt = Instant.now().plus(Duration.ofDays(1));
        private int drawQuota = 10;
        private final List<PrizeSpec> prizes = new ArrayList<>();
        private boolean listed = true;

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder window(Instant startAt, Instant endAt) {
            this.startAt = startAt;
            this.endAt = endAt;
            return this;
        }

        public Builder drawQuota(int drawQuota) {
            this.drawQuota = drawQuota;
            return this;
        }

        /** 先不加進 {@link CampaignRedisKeys#LIVE_CAMPAIGNS}：outbox relay 看不到它，直到 {@link #list(long)}。 */
        public Builder unlisted() {
            this.listed = false;
            return this;
        }

        /** 依呼叫順序累加 ppm 區間（等同 Prize 順序）。 */
        public Builder prize(long id, String name, int ppm, int stock) {
            prizes.add(new PrizeSpec(id, name, ppm, stock));
            return this;
        }

        /** 寫入 Redis 並回傳新的 campaignId。 */
        public long load() {
            long id = NEXT_ID.getAndIncrement();
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put(CampaignRedisKeys.META_STATUS, status);
            meta.put(CampaignRedisKeys.META_START_AT, String.valueOf(startAt.toEpochMilli()));
            meta.put(CampaignRedisKeys.META_END_AT, String.valueOf(endAt.toEpochMilli()));
            meta.put(CampaignRedisKeys.META_DRAW_QUOTA, String.valueOf(drawQuota));
            redis.opsForHash().putAll(CampaignRedisKeys.meta(id), meta);

            long cumulative = 0;
            for (PrizeSpec prize : prizes) {
                String prizeId = String.valueOf(prize.id());
                cumulative += prize.ppm();
                if (prize.ppm() > 0) {
                    redis.opsForZSet().add(CampaignRedisKeys.intervals(id), prizeId, cumulative);
                }
                redis.opsForHash().put(CampaignRedisKeys.prizeNames(id), prizeId, prize.name());
                redis.opsForHash().put(CampaignRedisKeys.stock(id), prizeId, String.valueOf(prize.stock()));
            }
            if (listed) {
                list(id);
            }
            return id;
        }
    }
}
