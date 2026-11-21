package com.spintowin.draw.outbox.service;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.redis.CampaignRedisKeys;
import com.spintowin.draw.config.OutboxRelayProperties;
import com.spintowin.draw.outbox.redis.OutboxStream;
import com.spintowin.draw.outbox.redis.OutboxStream.OutboxEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * outbox relay：把每個 Campaign 的 outbox stream 中的 draw.completed 事件轉送到 Kafka，
 * Kafka 確認（acks=all、idempotent producer）後才 XACK 並刪除。投遞是 at-least-once。
 * <p>
 * 每個 draw-service 實例在背景單一執行緒上每 {@code poll-interval} 跑一輪：
 * <ol>
 *   <li>從 {@code campaigns:live}（campaign-service 維護）找出所有 Campaign 的 outbox stream。</li>
 *   <li>每 {@code claim-interval}：接手 pending 超過 {@code claim-min-idle} 的事件並重送
 *       （崩潰實例讀走但沒 ack 的、或自己先前送 Kafka 失敗的），並清掉閒置的舊 consumer。</li>
 *   <li>以 consumer group 讀新事件並送出；送出失敗的事件留在 pending，下一次接手時重送。
 *       無法解析的事件記 ERROR 後移到 dead-letter stream，不會卡住其他事件。接手失敗也不會跳過讀新事件。</li>
 *   <li>Campaign 已 ENDED 且 stream 已清空時，這個實例只在接手的回合檢查它：結束的交易 rollback 後
 *       （Redis 補償回 ACTIVE）或 stream 又有事件時，就恢復轉送，不會永久略過。</li>
 * </ol>
 * message key 為 campaignId，同一 Campaign 的事件進同一個 partition；但重送可能讓順序改變，
 * 消費端應以 drawId 去重，不依賴順序。
 */
@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 一輪中每個 Campaign 最多連續讀幾批新事件，避免單一熱門 Campaign 佔住整輪。 */
    private static final int MAX_BATCHES_PER_CAMPAIGN = 10;

    private final OutboxStream stream;
    private final KafkaTemplate<String, DrawCompletedEvent> kafka;
    private final OutboxRelayProperties properties;
    private final String consumerName;
    private final Set<Long> drained = ConcurrentHashMap.newKeySet();

    private volatile ScheduledExecutorService executor;
    private long nextClaimAt;

    public OutboxRelay(OutboxStream stream, KafkaTemplate<String, DrawCompletedEvent> kafka,
                       OutboxRelayProperties properties) {
        this.stream = stream;
        this.kafka = kafka;
        this.properties = properties;
        this.consumerName = StringUtils.hasText(properties.consumerName())
                ? properties.consumerName()
                : hostname() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.nextClaimAt = System.nanoTime();
    }

    /** 跑一輪轉送（背景執行緒呼叫；不會丟出例外）。 */
    void relayOnce() {
        boolean claimDue = System.nanoTime() - nextClaimAt >= 0;
        if (claimDue) {
            nextClaimAt = System.nanoTime() + properties.claimInterval().toNanos();
        }
        List<Long> campaignIds;
        try {
            campaignIds = stream.liveCampaignIds();
        } catch (RuntimeException e) {
            log.warn("Outbox relay cannot list live campaigns: {}", e.toString());
            return;
        }
        for (long campaignId : campaignIds) {
            try {
                if (drained.contains(campaignId) && !resumeIfNoLongerDrained(campaignId, claimDue)) {
                    continue;
                }
                relayCampaign(campaignId, claimDue);
            } catch (RuntimeException e) {
                log.warn("Outbox relay failed for campaign {}: {}", campaignId, e.toString());
            }
        }
    }

    /**
     * 已略過的 Campaign 在接手回合重新確認：ENDED 可能被 rollback 補償回 ACTIVE，之後的抽獎又會寫入 outbox。
     *
     * @return true = 不再是已清空的狀態，這一輪照常轉送
     */
    private boolean resumeIfNoLongerDrained(long campaignId, boolean claimDue) {
        if (!claimDue || stream.isDrained(campaignId)) {
            return false;
        }
        drained.remove(campaignId);
        log.info("Campaign {} is no longer ended-and-empty; relaying its outbox again", campaignId);
        return true;
    }

    private void relayCampaign(long campaignId, boolean claimDue) {
        stream.ensureGroup(campaignId);
        boolean sawEvents = false;
        if (claimDue) {
            List<OutboxEntry> stale = stream.claimStale(campaignId, consumerName, properties.claimMinIdle(),
                    properties.batchSize());
            if (!stale.isEmpty()) {
                sawEvents = true;
                log.info("Redelivering {} un-acked outbox event(s) of campaign {}", stale.size(), campaignId);
                publish(campaignId, stale); // 失敗的留在 pending 等下次接手；新事件照常讀取

            }
            stream.removeStaleConsumers(campaignId, consumerName, properties.staleConsumerIdle());
        }
        for (int batch = 0; batch < MAX_BATCHES_PER_CAMPAIGN; batch++) {
            List<OutboxEntry> entries = stream.readNew(campaignId, consumerName, properties.batchSize());
            if (entries.isEmpty()) {
                break;
            }
            sawEvents = true;
            if (!publish(campaignId, entries) || entries.size() < properties.batchSize()) {
                break;
            }
        }
        if (claimDue && !sawEvents && stream.isDrained(campaignId)) {
            drained.add(campaignId);
            log.info("Campaign {} has ended and its outbox is empty; no longer relaying it", campaignId);
        }
    }

    /**
     * 送出一批事件並等待 Kafka 確認，只 ack 確認成功的。
     *
     * @return false = 至少一筆沒有送出（留在 pending，之後重送）
     */
    private boolean publish(long campaignId, List<OutboxEntry> entries) {
        List<RecordId> sent = new ArrayList<>(entries.size());
        boolean allSent = true;
        List<CompletableFuture<SendResult<String, DrawCompletedEvent>>> futures = new ArrayList<>(entries.size());
        for (OutboxEntry entry : entries) {
            if (entry.event() == null) {
                deadLetter(campaignId, entry);
                futures.add(null);
                continue;
            }
            try {
                futures.add(kafka.send(DrawCompletedEvent.TOPIC, String.valueOf(campaignId), entry.event()));
            } catch (RuntimeException e) {
                futures.add(CompletableFuture.failedFuture(e));
            }
        }
        long deadline = System.nanoTime() + properties.sendTimeout().toNanos();
        for (int i = 0; i < entries.size(); i++) {
            CompletableFuture<?> future = futures.get(i);
            if (future == null) {
                continue; // 無法解析，已移到 dead-letter stream
            }
            try {
                future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                sent.add(entries.get(i).id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                allSent = false;
                break;
            } catch (ExecutionException | TimeoutException e) {
                allSent = false;
                log.warn("Sending outbox event {} of campaign {} to Kafka failed; it stays pending for redelivery: {}",
                        entries.get(i).id(), campaignId, String.valueOf(e instanceof ExecutionException ? e.getCause() : e));
            }
        }
        stream.acknowledge(campaignId, sent);
        return allSent;
    }

    /** 無法解析的事件重送也不會成功：記 ERROR 並移到 dead-letter stream；移動失敗就留在 pending，下次接手再試。 */
    private void deadLetter(long campaignId, OutboxEntry entry) {
        log.error("Unreadable outbox entry {} of campaign {} moved to {}: {}", entry.id(), campaignId,
                CampaignRedisKeys.outboxDeadLetter(campaignId), entry.fields());
        try {
            stream.deadLetter(campaignId, entry);
        } catch (RuntimeException e) {
            log.warn("Moving unreadable outbox entry {} of campaign {} to the dead-letter stream failed; "
                    + "it stays pending: {}", entry.id(), campaignId, e.toString());
        }
    }

    private void relaySafely() {
        try {
            relayOnce();
        } catch (Throwable e) {
            log.error("Outbox relay round failed", e);
        }
    }

    @Override
    public void start() {
        if (!properties.enabled() || executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "outbox-relay");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::relaySafely, 0, properties.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS);
        log.info("Outbox relay started as consumer {} of group {}", consumerName, OutboxStream.GROUP);
    }

    /** 停止前等目前這一輪送完；沒送完的事件留在 Redis，由其他實例（或重啟後）接手。 */
    @Override
    public void stop() {
        ScheduledExecutorService running = executor;
        if (running == null) {
            return;
        }
        running.shutdown();
        try {
            if (!running.awaitTermination(properties.sendTimeout().toMillis() + 1_000, TimeUnit.MILLISECONDS)) {
                running.shutdownNow();
            }
        } catch (InterruptedException e) {
            running.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "draw-service";
        }
    }
}
