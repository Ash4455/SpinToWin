package com.spintowin.draw.outbox.redis;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.redis.CampaignRedisKeys;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.StreamDeletionPolicy;
import org.springframework.data.redis.connection.RedisStreamCommands.XDelOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每個 Campaign 的 outbox stream（{@link CampaignRedisKeys#outbox}）與其 consumer group 的 Redis 存取。
 * <p>
 * 所有 draw-service 實例共用同一個 consumer group {@value #GROUP}，各自以不同的 consumer 名稱讀取，
 * 因此一筆事件同一時間只會交給一個實例。事件在 Kafka 確認後以 XACKDEL 確認並刪除，
 * stream 中只留下尚未送出的事件（長度即為積壓量），不需要另外修剪。
 * <p>
 * 每個指令只碰單一 Campaign 的 key（同一 hash tag），全域索引 {@link CampaignRedisKeys#LIVE_CAMPAIGNS} 另外讀取，
 * 因此可在 Redis Cluster 上執行。
 */
@Component
public class OutboxStream {

    /** 所有 draw-service 實例共用的 consumer group。 */
    public static final String GROUP = "draw-outbox-relay";

    /** Lua XADD 的欄位名稱，值為事件 JSON。 */
    static final String EVENT_FIELD = "event";

    /** dead-letter stream 中記錄原本 outbox entry id 的欄位。 */
    static final String OUTBOX_ID_FIELD = "outboxId";

    private static final Logger log = LoggerFactory.getLogger(OutboxStream.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Set<Long> groupsKnown = ConcurrentHashMap.newKeySet();

    public OutboxStream(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** campaign-service 載入過 Redis 的所有 Campaign（只會增加，不會移除）。 */
    public List<Long> liveCampaignIds() {
        Set<String> members = redis.opsForSet().members(CampaignRedisKeys.LIVE_CAMPAIGNS);
        if (members == null) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                ids.add(Long.parseLong(member));
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric member '{}' of {}", member, CampaignRedisKeys.LIVE_CAMPAIGNS);
            }
        }
        return ids;
    }

    /**
     * 建立 consumer group（若不存在），從 stream 開頭讀起：relay 第一次看到 Campaign 之前就已寫入的事件也會送出。
     * MKSTREAM：stream 尚不存在時一併建立。多個實例同時建立時，後到的會收到 BUSYGROUP，視為成功。
     */
    public void ensureGroup(long campaignId) {
        if (groupsKnown.contains(campaignId)) {
            return;
        }
        try {
            redis.opsForStream().createGroup(CampaignRedisKeys.outbox(campaignId), ReadOffset.from("0-0"), GROUP);
        } catch (DataAccessException e) {
            if (!hasMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
        groupsKnown.add(campaignId);
    }

    /** 讀取尚未交給任何 consumer 的新事件（XREADGROUP ... &gt;，不阻塞）。 */
    public List<OutboxEntry> readNew(long campaignId, String consumer, int count) {
        String key = CampaignRedisKeys.outbox(campaignId);
        List<MapRecord<String, Object, Object>> records = withGroup(campaignId, () -> redis.opsForStream().read(
                Consumer.from(GROUP, consumer),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(key, ReadOffset.lastConsumed())));
        return toEntries(campaignId, records);
    }

    /**
     * 接手 pending 超過 {@code minIdle} 的事件（任何 consumer 的，包含自己先前送出失敗的）：
     * XPENDING IDLE 找出候選，再 XCLAIM（同樣帶 min-idle，若其他實例剛接手就不會重複接手）。
     */
    public List<OutboxEntry> claimStale(long campaignId, String consumer, Duration minIdle, int count) {
        String key = CampaignRedisKeys.outbox(campaignId);
        List<RecordId> stale = withGroup(campaignId, () -> {
            List<RecordId> ids = new ArrayList<>();
            for (PendingMessage pending : redis.opsForStream().pending(key, GROUP, Range.unbounded(), count, minIdle)) {
                ids.add(pending.getId());
            }
            return ids;
        });
        if (stale == null || stale.isEmpty()) {
            return List.of();
        }
        List<MapRecord<String, Object, Object>> records = redis.opsForStream()
                .claim(key, GROUP, consumer, minIdle, stale.toArray(RecordId[]::new));
        return toEntries(campaignId, records);
    }

    /** Kafka 已確認：XACKDEL（ACKED 政策：只在所有 consumer group 都已確認時才刪除）。 */
    public void acknowledge(long campaignId, List<RecordId> ids) {
        if (ids.isEmpty()) {
            return;
        }
        redis.opsForStream().acknowledgeAndDelete(CampaignRedisKeys.outbox(campaignId), GROUP,
                XDelOptions.deletionPolicy(StreamDeletionPolicy.removeAcknowledged()), ids.toArray(RecordId[]::new));
    }

    /**
     * 把無法解析的事件移到 {@link CampaignRedisKeys#outboxDeadLetter}（原始欄位加上 {@value #OUTBOX_ID_FIELD}），
     * 再從 outbox 確認並刪除，讓它不會一直留在 pending、被反覆接手。先寫 dead-letter 再刪除：
     * 兩步之間崩潰只會讓它在 dead-letter stream 出現兩次，不會遺失。
     */
    public void deadLetter(long campaignId, OutboxEntry entry) {
        Map<String, String> fields = new LinkedHashMap<>(entry.fields());
        fields.put(OUTBOX_ID_FIELD, entry.id().getValue());
        redis.opsForStream().add(CampaignRedisKeys.outboxDeadLetter(campaignId), fields);
        acknowledge(campaignId, List.of(entry.id()));
    }

    /**
     * Campaign 已 ENDED 且 outbox 沒有任何事件（包含 pending）。ENDED 之後抽獎 Lua 不會再 XADD，
     * 因此先看到 ENDED、再看到 stream 為空，就代表這個 Campaign 不會再有事件。
     */
    public boolean isDrained(long campaignId) {
        Object status = redis.opsForHash().get(CampaignRedisKeys.meta(campaignId), CampaignRedisKeys.META_STATUS);
        if (!"ENDED".equals(status)) {
            return false;
        }
        Long length = redis.opsForStream().size(CampaignRedisKeys.outbox(campaignId));
        return length == null || length == 0;
    }

    /** 刪除閒置超過 {@code idle} 且沒有 pending 的其他 consumer（重啟或縮容後留下的舊名稱）。 */
    public void removeStaleConsumers(long campaignId, String self, Duration idle) {
        String key = CampaignRedisKeys.outbox(campaignId);
        StreamInfo.XInfoConsumers consumers = withGroup(campaignId, () -> redis.opsForStream().consumers(key, GROUP));
        if (consumers == null) {
            return;
        }
        consumers.forEach(c -> {
            if (!c.consumerName().equals(self) && c.pendingCount() == 0 && c.idleTime().compareTo(idle) > 0) {
                redis.opsForStream().deleteConsumer(key, Consumer.from(GROUP, c.consumerName()));
                log.info("Removed stale outbox consumer {} of campaign {}", c.consumerName(), campaignId);
            }
        });
    }

    /** stream 被刪除（例如 Redis 資料遺失）時 group 也會消失：NOGROUP 時忘掉它，下一輪重新建立。 */
    private <T> @Nullable T withGroup(long campaignId, java.util.function.Supplier<T> command) {
        try {
            return command.get();
        } catch (DataAccessException e) {
            if (hasMessage(e, "NOGROUP")) {
                groupsKnown.remove(campaignId);
                return null;
            }
            throw e;
        }
    }

    private List<OutboxEntry> toEntries(long campaignId, @Nullable List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<OutboxEntry> entries = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, String> fields = new LinkedHashMap<>();
            record.getValue().forEach((field, value) -> fields.put(String.valueOf(field), String.valueOf(value)));
            String payload = fields.get(EVENT_FIELD);
            entries.add(new OutboxEntry(campaignId, record.getId(), payload == null ? null : parse(payload), fields));
        }
        return entries;
    }

    private @Nullable DrawCompletedEvent parse(String payload) {
        try {
            StoredEvent stored = json.readValue(payload, StoredEvent.class);
            return new DrawCompletedEvent(stored.campaignId(), stored.userId(), stored.requestId(),
                    Instant.ofEpochMilli(stored.requestedAt()),
                    stored.results().stream()
                            .map(r -> new DrawCompletedEvent.Result(r.drawId(), r.prizeId(), r.prizeName()))
                            .toList());
        } catch (JacksonException | NullPointerException e) {
            return null;
        }
    }

    private static boolean hasMessage(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
                return true;
            }
        }
        return false;
    }

    /**
     * outbox 中的一筆事件。
     *
     * @param event  null = 內容無法解析（不送出，移到 dead-letter stream 供人工檢查）
     * @param fields 原始欄位（記錄錯誤與 dead-letter 用）
     */
    public record OutboxEntry(long campaignId, RecordId id, @Nullable DrawCompletedEvent event,
                              Map<String, String> fields) {
    }

    /** 抽獎 Lua 以 cjson 寫入的事件 JSON（requestedAt 為 epoch 毫秒；銘謝惠顧沒有 prizeId 與 prizeName）。 */
    private record StoredEvent(long campaignId, String userId, String requestId, long requestedAt,
                               List<StoredResult> results) {
    }

    private record StoredResult(String drawId, @Nullable Long prizeId, @Nullable String prizeName) {
    }
}
