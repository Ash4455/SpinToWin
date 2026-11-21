package com.spintowin.campaign.config;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.event.InvalidDrawEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.Map;

import static com.spintowin.common.event.TransientFailures.isTransient;

/**
 * draw.completed consumer 的失敗處理（{@link DefaultErrorHandler}，Boot 套用到 {@code @KafkaListener}；
 * 次數、間隔與 dead-letter topic 見 {@link CampaignConsumerProperties}）：
 * <ul>
 *   <li>MySQL 暫時不可用（連線、鎖等待逾時、deadlock）：指數退避無限重試，不跳過事件，等 MySQL 恢復；
 *       期間該 partition 停在這筆事件。</li>
 *   <li>無法解析的訊息（{@code ErrorHandlingDeserializer} 的 DeserializationException）與不符合合約的事件
 *       （{@link InvalidDrawEventException}）：不重試，直接送 dead-letter topic。</li>
 *   <li>其他例外：有限次重試，仍失敗就送 dead-letter topic，之後的事件繼續處理。</li>
 * </ul>
 * dead-letter topic 保留原始內容（無法解析的訊息保留原始 bytes）與 Spring Kafka 的例外 header，修正後可重新送回 draw.completed
 * （已處理事件表保證重送不會重複扣減）。送 dead-letter topic 失敗時視同處理失敗，事件會再被重試，不會遺失。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CampaignConsumerProperties.class)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    NewTopic drawCompletedDeadLetterTopic(CampaignConsumerProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.deadLetterReplicas())
                .build();
    }

    /**
     * Boot 自動設定的 producer（只用於 dead-letter topic）：無法解析的訊息是原始 bytes，
     * 其他失敗的事件以事件合約的 JSON（不帶 type header）寫出。
     */
    @Bean
    @SuppressWarnings("unchecked")
    DefaultKafkaProducerFactoryCustomizer deadLetterValueSerializer() {
        return factory -> ((DefaultKafkaProducerFactory<Object, Object>) factory)
                .setValueSerializerSupplier(() -> new DelegatingByTypeSerializer(Map.<Class<?>, Serializer<?>>of(
                        byte[].class, new ByteArraySerializer(),
                        DrawCompletedEvent.class, new JacksonJsonSerializer<DrawCompletedEvent>().noTypeInfo())));
    }

    @Bean
    DefaultErrorHandler drawCompletedErrorHandler(KafkaOperations<Object, Object> kafkaTemplate,
                                                  CampaignConsumerProperties properties) {
        String deadLetterTopic = properties.deadLetterTopic();
        DeadLetterPublishingRecoverer deadLetter = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(deadLetterTopic, -1)); // -1：依 key 選 partition

        ExponentialBackOffWithMaxRetries bounded = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        bounded.setInitialInterval(properties.retryInitialInterval().toMillis());
        bounded.setMultiplier(2.0);
        bounded.setMaxInterval(properties.retryMaxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("Giving up on {} message at {}-{}@{} (key {}); sending it to {}: {}",
                    DrawCompletedEvent.TOPIC, record.topic(), record.partition(), record.offset(), record.key(),
                    deadLetterTopic, NestedExceptionUtils.getMostSpecificCause(exception).toString());
            deadLetter.accept(record, exception);
        }, bounded);
        handler.addNotRetryableExceptions(InvalidDrawEventException.class);
        handler.setBackOffFunction((record, exception) -> isTransient(exception) ? untilAvailable(properties) : null);
        return handler;
    }

    private static BackOff untilAvailable(CampaignConsumerProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(properties.retryMaxInterval().toMillis());
        return backOff; // 沒有次數與總時間上限
    }
}
