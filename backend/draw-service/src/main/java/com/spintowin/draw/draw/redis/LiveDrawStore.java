package com.spintowin.draw.draw.redis;

import com.spintowin.common.redis.CampaignRedisKeys;
import com.spintowin.common.redis.IdempotencyScope;
import com.spintowin.draw.draw.model.DrawQuotaStatus;
import com.spintowin.draw.draw.model.DrawRequestResult;
import com.spintowin.draw.draw.model.DrawResult;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 在 Redis 執行抽獎 Lua（{@code redis/draw.lua}）。key 規格見 {@link CampaignRedisKeys}；
 * 一個 script 只存取同一 {@code {campaignId}} hash tag 的 key，因此是原子的，也能在 Redis Cluster 上執行。
 * 新判定的請求會在同一個 script 中把 draw.completed 事件 XADD 到 outbox stream（見 {@code OutboxRelay}）。
 */
@Component
public class LiveDrawStore {

    /** Idempotency 結果的保存時間。 */
    static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> DRAW = RedisScript.of(new ClassPathResource("redis/draw.lua"), List.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public LiveDrawStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /**
     * @param scope     哪位使用者在哪個 Campaign 以哪個 Idempotency-Key 抽獎
     * @param requestId 這次請求的新識別（重送時會被忽略，改回傳先前的 requestId）
     * @param ppms      每一抽的隨機落點，長度即 count
     */
    public DrawAttempt draw(IdempotencyScope scope, String requestId, int[] ppms) {
        long campaignId = scope.campaignId();
        String userId = scope.userId();
        List<String> keys = List.of(
                CampaignRedisKeys.meta(campaignId),
                CampaignRedisKeys.intervals(campaignId),
                CampaignRedisKeys.prizeNames(campaignId),
                CampaignRedisKeys.stock(campaignId),
                CampaignRedisKeys.used(campaignId, userId),
                CampaignRedisKeys.idempotency(scope),
                CampaignRedisKeys.outbox(campaignId));
        List<String> args = new ArrayList<>(5 + ppms.length);
        args.add(requestId);
        args.add(String.valueOf(IDEMPOTENCY_TTL.toSeconds()));
        args.add(String.valueOf(ppms.length));
        for (int ppm : ppms) {
            args.add(String.valueOf(ppm));
        }
        // outbox 事件（draw.completed）的內容
        args.add(userId);
        args.add(String.valueOf(campaignId));

        List<?> reply = redis.execute(DRAW, keys, args.toArray());
        String status = (String) reply.get(0);
        return switch (status) {
            case "DRAWN" -> new DrawAttempt.Drawn(parse((String) reply.get(1)), false);
            case "REPLAYED" -> new DrawAttempt.Drawn(parse((String) reply.get(1)), true);
            case "QUOTA_EXCEEDED" -> new DrawAttempt.QuotaExceeded(Math.toIntExact((Long) reply.get(1)));
            case "NOT_LOADED", "NOT_ACTIVE", "NOT_STARTED", "ENDED" ->
                    new DrawAttempt.NotActive(DrawAttempt.NotActive.Reason.valueOf(status));
            default -> throw new IllegalStateException("Unexpected draw script reply: " + status);
        };
    }

    /** @return empty = Campaign 不在 Redis 中（尚未上線） */
    public Optional<DrawQuotaStatus> quota(long campaignId, String userId) {
        Object drawQuota = redis.opsForHash().get(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.META_DRAW_QUOTA);
        if (drawQuota == null) {
            return Optional.empty();
        }
        String used = redis.opsForValue().get(CampaignRedisKeys.used(campaignId, userId));
        return Optional.of(DrawQuotaStatus.of(Integer.parseInt((String) drawQuota),
                used == null ? 0 : Integer.parseInt(used)));
    }

    private DrawRequestResult parse(String payload) {
        StoredDraw stored = json.readValue(payload, StoredDraw.class);
        List<DrawResult> results = stored.results().stream()
                .map(r -> new DrawResult(r.drawId(), r.prizeId(), r.prizeName()))
                .toList();
        return new DrawRequestResult(stored.requestId(), Instant.ofEpochMilli(stored.drawnAt()),
                stored.remainingDraws(), results);
    }

    /** Lua 存進 Idempotency key、也回傳給 Java 的 JSON（cjson 編碼；銘謝惠顧時沒有 prizeId 與 prizeName）。 */
    private record StoredDraw(String requestId, long drawnAt, int remainingDraws, List<StoredResult> results) {
    }

    private record StoredResult(String drawId, @Nullable Long prizeId, @Nullable String prizeName) {
    }
}
