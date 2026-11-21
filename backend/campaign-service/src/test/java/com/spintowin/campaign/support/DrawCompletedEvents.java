package com.spintowin.campaign.support;

import com.spintowin.common.event.DrawCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.fail;

/**
 * 以 draw-service 的角度發送 {@value DrawCompletedEvent#TOPIC} 事件：直接寫事件合約的 JSON
 * （key = campaignId，不帶 type header），不依賴 draw-service 的程式。
 * 也能讀回 campaign-service 送進 dead-letter topic 的訊息。
 */
public class DrawCompletedEvents implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private final KafkaProducer<String, String> producer;

    DrawCompletedEvents(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
    }

    /**
     * 一次抽獎請求的事件 JSON：每個元素是一抽的結果，prizeId 為 null 表示銘謝惠顧。
     * drawId = {@code <requestId>-<序號>}。
     */
    public static String json(long campaignId, String requestId, Long... prizeIds) {
        String results = IntStream.range(0, prizeIds.length)
                .mapToObj(i -> prizeIds[i] == null
                        ? """
                          {"drawId":"%s-%d","prizeId":null,"prizeName":null}""".formatted(requestId, i + 1)
                        : """
                          {"drawId":"%s-%d","prizeId":%d,"prizeName":"Prize %d"}"""
                                .formatted(requestId, i + 1, prizeIds[i], prizeIds[i]))
                .collect(Collectors.joining(","));
        return """
                {"campaignId":%d,"userId":"8f9c7a52-0000-4000-8000-000000000001","requestId":"%s",\
                "requestedAt":"2027-01-21T08:00:00.123Z","results":[%s]}"""
                .formatted(campaignId, requestId, results);
    }

    /** 發送一次抽獎請求的事件（新的 requestId），回傳其 JSON（可再次 {@link #send} 模擬重複投遞）。 */
    public String drawn(long campaignId, Long... prizeIds) {
        String json = json(campaignId, UUID.randomUUID().toString(), prizeIds);
        send(campaignId, json);
        return json;
    }

    /** 以 key = campaignId 發送原始訊息內容，等 broker 確認。 */
    public void send(long campaignId, String value) {
        try {
            producer.send(new ProducerRecord<>(DrawCompletedEvent.TOPIC, String.valueOf(campaignId), value)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 從頭讀 {@code topic}，等到出現 key = campaignId 的訊息，回傳這些訊息的內容。 */
    public List<String> awaitMessages(String topic, long campaignId) {
        String key = String.valueOf(campaignId);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            List<TopicPartition> partitions = List.of();
            while (partitions.isEmpty()) {
                partitions = consumer.partitionsFor(topic).stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .toList();
                if (partitions.isEmpty() && System.nanoTime() > deadline) {
                    fail("Kafka topic %s does not exist", topic);
                }
            }
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<String> values = new ArrayList<>();
            while (values.isEmpty()) {
                if (System.nanoTime() > deadline) {
                    fail("Expected messages with key %s on %s within %s", key, topic, TIMEOUT);
                }
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(record.key())) {
                        values.add(record.value());
                    }
                }
            }
            return values;
        }
    }

    @Override
    public void close() {
        producer.close();
    }
}
