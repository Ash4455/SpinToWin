package com.spintowin.draw.support;

import com.spintowin.common.event.DrawCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.fail;

/**
 * 從 Kafka topic {@value DrawCompletedEvent#TOPIC} 讀回事件（從頭讀所有 partition，不加入 consumer group），
 * 依 message key（campaignId）分組保存。每個測試用新的 campaignId，因此只看自己 Campaign 的事件即可。
 * <p>
 * value 以原始 JSON 字串保存（事件合約）；{@link #awaitEvents} 另以消費端會用的 {@link JacksonJsonDeserializer}
 * 轉回 {@link DrawCompletedEvent}。
 */
public class DrawCompletedTopic implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private KafkaConsumer<String, String> consumer;
    private final Map<String, List<String>> jsonByKey = new LinkedHashMap<>();
    private final JacksonJsonDeserializer<DrawCompletedEvent> deserializer =
            new JacksonJsonDeserializer<>(DrawCompletedEvent.class, false);

    DrawCompletedTopic(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /** 等到這個 Campaign 至少有 {@code count} 筆事件，依到達順序回傳原始 JSON。 */
    public List<String> awaitJson(long campaignId, int count) {
        long deadline = System.nanoTime() + DEFAULT_TIMEOUT.toNanos();
        while (json(campaignId).size() < count) {
            if (System.nanoTime() > deadline) {
                fail("Expected %d draw.completed events for campaign %d within %s but got %s",
                        count, campaignId, DEFAULT_TIMEOUT, json(campaignId));
            }
            poll();
        }
        return json(campaignId);
    }

    /** 同 {@link #awaitJson}，但以消費端的 Jackson 3 deserializer 轉回事件。 */
    public List<DrawCompletedEvent> awaitEvents(long campaignId, int count) {
        return awaitJson(campaignId, count).stream()
                .map(json -> deserializer.deserialize(DrawCompletedEvent.TOPIC, json.getBytes()))
                .toList();
    }

    private List<String> json(long campaignId) {
        return List.copyOf(jsonByKey.getOrDefault(String.valueOf(campaignId), List.of()));
    }

    private void poll() {
        for (ConsumerRecord<String, String> record : consumer().poll(Duration.ofMillis(200))) {
            jsonByKey.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(record.value());
        }
    }

    private KafkaConsumer<String, String> consumer() {
        if (consumer == null) {
            KafkaConsumer<String, String> created = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
            List<TopicPartition> partitions = created.partitionsFor(DrawCompletedEvent.TOPIC).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            if (partitions.isEmpty()) {
                created.close();
                fail("Kafka topic %s does not exist", DrawCompletedEvent.TOPIC);
            }
            created.assign(partitions);
            created.seekToBeginning(partitions);
            consumer = created;
        }
        return consumer;
    }

    /** topic 的 partition 數（驗證 topic 是以明確設定建立，而不是 broker 自動建立的 1 個 partition）。 */
    public int partitionCount() {
        return consumer().partitionsFor(DrawCompletedEvent.TOPIC).size();
    }

    @Override
    public void close() {
        if (consumer != null) {
            consumer.close();
        }
    }
}
