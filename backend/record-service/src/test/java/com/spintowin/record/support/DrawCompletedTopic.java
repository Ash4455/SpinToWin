package com.spintowin.record.support;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.fail;

/**
 * 扮演 draw-service 的 outbox relay：以純 {@link KafkaProducer} 把事件 JSON（事件合約本身，與 draw-service 程式碼無關）
 * 送到 {@value DrawCompletedEvent#TOPIC}，message key = campaignId。也能從頭讀回 dead-letter topic 的內容。
 */
public class DrawCompletedTopic implements AutoCloseable {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String bootstrapServers;
    private final String deadLetterTopic;
    private final KafkaProducer<String, String> producer;
    private KafkaConsumer<String, String> deadLetterConsumer;
    private final Map<String, List<String>> deadLettersByKey = new LinkedHashMap<>();

    DrawCompletedTopic(String bootstrapServers, String deadLetterTopic) {
        this.bootstrapServers = bootstrapServers;
        this.deadLetterTopic = deadLetterTopic;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
    }

    /** 送出一筆事件並等 broker 確認；key 為 campaignId，所以同一 Campaign 的事件落在同一個 partition、依序消費。 */
    public void publish(long campaignId, String json) {
        try {
            producer.send(new ProducerRecord<>(DrawCompletedEvent.TOPIC, String.valueOf(campaignId), json))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish test event", e);
        }
    }

    /** 等到 dead-letter topic 中 key 為這個 Campaign 的訊息至少有 {@code count} 筆，依到達順序回傳 value。 */
    public List<String> awaitDeadLetters(long campaignId, int count) {
        String key = String.valueOf(campaignId);
        long deadline = System.nanoTime() + DEFAULT_TIMEOUT.toNanos();
        while (deadLettersByKey.getOrDefault(key, List.of()).size() < count) {
            if (System.nanoTime() > deadline) {
                fail("Expected %d dead letters for campaign %d within %s but got %s",
                        count, campaignId, DEFAULT_TIMEOUT, deadLettersByKey.get(key));
            }
            for (ConsumerRecord<String, String> record : deadLetterConsumer().poll(Duration.ofMillis(200))) {
                deadLettersByKey.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(record.value());
            }
        }
        return List.copyOf(deadLettersByKey.get(key));
    }

    private KafkaConsumer<String, String> deadLetterConsumer() {
        if (deadLetterConsumer == null) {
            KafkaConsumer<String, String> created = new KafkaConsumer<>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false));
            List<TopicPartition> partitions = created.partitionsFor(deadLetterTopic).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            if (partitions.isEmpty()) {
                created.close();
                fail("Kafka topic %s does not exist", deadLetterTopic);
            }
            created.assign(partitions);
            created.seekToBeginning(partitions);
            deadLetterConsumer = created;
        }
        return deadLetterConsumer;
    }

    @Override
    public void close() {
        producer.close();
        if (deadLetterConsumer != null) {
            deadLetterConsumer.close();
        }
    }
}
