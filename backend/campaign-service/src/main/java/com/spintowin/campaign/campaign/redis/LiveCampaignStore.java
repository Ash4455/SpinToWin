package com.spintowin.campaign.campaign.redis;

import com.spintowin.campaign.campaign.entity.CampaignStatus;
import com.spintowin.common.redis.CampaignRedisKeys;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 讀寫 Redis 中 Campaign 的 live state（key 規格見 {@link CampaignRedisKeys}）。每個操作都是單一 Lua script，
 * 只存取同一 {@code {campaignId}} hash tag 的 key，因此是原子的，並與 draw-service 的抽獎 Lua 序列化。
 * <p>
 * 不碰使用者已用次數、Idempotency 結果與 outbox：那些只由 draw-service 寫入，MySQL 也沒有它們的副本。
 */
@Component
public class LiveCampaignStore {

    private static final String REPLACE = "replace";
    private static final String IF_ABSENT = "if-absent";

    private static final RedisScript<Long> LOAD = script("redis/load-campaign.lua", Long.class);
    private static final RedisScript<Long> SYNC_PRIZES = script("redis/sync-prizes.lua", Long.class);
    private static final RedisScript<Long> SET_STATUS = script("redis/set-status.lua", Long.class);
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADJUST_STOCK = script("redis/adjust-stock.lua", List.class);

    private final StringRedisTemplate redis;

    public LiveCampaignStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 以 {@code campaign} 覆寫 live state（設定、區間、名稱與 Stock）。 */
    public void replace(LiveCampaign campaign) {
        load(campaign, REPLACE);
    }

    /**
     * 只有在 Campaign 尚未載入時才寫入；已載入時保留 Redis 中的即時 Stock。
     *
     * @return true = 已寫入，false = 已存在而略過
     */
    public boolean loadIfAbsent(LiveCampaign campaign) {
        return load(campaign, IF_ABSENT);
    }

    /**
     * 重寫 ppm 區間與 Prize 名稱，Stock 不變。
     *
     * @param prizes 依 Prize 順序排列的完整 Prize 清單
     * @return false = Campaign 不在 Redis 中，什麼都沒做
     */
    public boolean syncPrizes(long campaignId, List<LivePrize> prizes) {
        List<String> args = new ArrayList<>();
        List<String> upperBounds = upperBounds(prizes);
        for (int i = 0; i < prizes.size(); i++) {
            args.add(String.valueOf(prizes.get(i).id()));
            args.add(prizes.get(i).name());
            args.add(upperBounds.get(i));
        }
        Long result = redis.execute(SYNC_PRIZES,
                List.of(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.intervals(campaignId),
                        CampaignRedisKeys.prizeNames(campaignId)),
                args.toArray());
        return result == 1L;
    }

    /** @return false = Campaign 不在 Redis 中，什麼都沒做 */
    public boolean setStatus(long campaignId, CampaignStatus status) {
        Long result = redis.execute(SET_STATUS, List.of(CampaignRedisKeys.meta(campaignId)),
                CampaignRedisKeys.META_STATUS, status.name());
        return result == 1L;
    }

    /** 原子地把 {@code delta} 加到剩餘 Stock；結果會是負數時不套用。 */
    public StockAdjustment adjustStock(long campaignId, long prizeId, int delta) {
        List<?> result = redis.execute(ADJUST_STOCK, List.of(CampaignRedisKeys.stock(campaignId)),
                String.valueOf(prizeId), String.valueOf(delta));
        long code = (Long) result.get(0);
        long remaining = (Long) result.get(1);
        StockAdjustment.Outcome outcome = code == 1L ? StockAdjustment.Outcome.APPLIED
                : code == -1L ? StockAdjustment.Outcome.WOULD_BE_NEGATIVE
                : StockAdjustment.Outcome.NOT_LOADED;
        return new StockAdjustment(outcome, remaining);
    }

    /** @return prizeId → 即時剩餘 Stock；空 = Campaign 不在 Redis 中 */
    public Map<Long, Integer> remainingStock(long campaignId) {
        Map<Long, Integer> stock = new HashMap<>();
        redis.opsForHash().entries(CampaignRedisKeys.stock(campaignId)).forEach((prizeId, remaining) ->
                stock.put(Long.valueOf(prizeId.toString()), Integer.valueOf(remaining.toString())));
        return stock;
    }

    /** 移除設定、區間、名稱與 Stock（上線失敗時的補償）。 */
    public void unload(long campaignId) {
        redis.delete(List.of(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.intervals(campaignId),
                CampaignRedisKeys.prizeNames(campaignId), CampaignRedisKeys.stock(campaignId)));
    }

    private boolean load(LiveCampaign campaign, String mode) {
        List<String> meta = List.of(
                CampaignRedisKeys.META_STATUS, campaign.status().name(),
                CampaignRedisKeys.META_START_AT, String.valueOf(campaign.startAt().toEpochMilli()),
                CampaignRedisKeys.META_END_AT, String.valueOf(campaign.endAt().toEpochMilli()),
                CampaignRedisKeys.META_DRAW_QUOTA, String.valueOf(campaign.drawQuota()));

        List<String> args = new ArrayList<>();
        args.add(mode);
        args.add(String.valueOf(meta.size()));
        args.addAll(meta);
        List<String> upperBounds = upperBounds(campaign.prizes());
        for (int i = 0; i < campaign.prizes().size(); i++) {
            LivePrize prize = campaign.prizes().get(i);
            args.add(String.valueOf(prize.id()));
            args.add(prize.name());
            args.add(upperBounds.get(i));
            args.add(String.valueOf(prize.remainingStock()));
        }

        long id = campaign.id();
        Long result = redis.execute(LOAD,
                List.of(CampaignRedisKeys.meta(id), CampaignRedisKeys.intervals(id),
                        CampaignRedisKeys.prizeNames(id), CampaignRedisKeys.stock(id)),
                args.toArray());
        // 全域索引不帶 hash tag，不能放進同一個 script（Redis Cluster 會 CROSSSLOT），因此另外寫
        redis.opsForSet().add(CampaignRedisKeys.LIVE_CAMPAIGNS, String.valueOf(id));
        return result == 1L;
    }

    /**
     * 依 Prize 順序累加 ppm，得到各 Prize 區間的上界（不含）；ppm 為 0 的 Prize 沒有區間，以空字串表示。
     * 機率總和 ≤ 1,000,000 已由寫入 MySQL 前的驗證保證。
     */
    private static List<String> upperBounds(List<LivePrize> prizes) {
        List<String> bounds = new ArrayList<>(prizes.size());
        long cumulative = 0;
        for (LivePrize prize : prizes) {
            cumulative += prize.probabilityPpm();
            bounds.add(prize.probabilityPpm() == 0 ? "" : String.valueOf(cumulative));
        }
        return bounds;
    }

    private static <T> RedisScript<T> script(String path, Class<T> resultType) {
        return RedisScript.of(new ClassPathResource(path), resultType);
    }
}
