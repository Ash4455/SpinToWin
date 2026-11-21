package com.spintowin.record.config;

import com.spintowin.common.event.InvalidDrawEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

import static com.spintowin.common.event.TransientFailures.isTransient;

/**
 * draw.completed consumer 的錯誤處理：
 * <ul>
 *   <li>無法解析或內容不合法的事件（{@link InvalidDrawEventException}）：不重試，直接送進 dead-letter topic。</li>
 *   <li>資料庫暫時無法使用（連線失敗、lock timeout、deadlock…）：以指數退避<b>無限重試</b>，不跳過事件；
 *       此時任何事件都寫不進去，擋住 partition 是正確的。</li>
 *   <li>其他錯誤（例如資料超出欄位長度）：有限次重試後送進 dead-letter topic，後面的事件照常處理。</li>
 * </ul>
 * dead-letter 訊息保留原始 key 與 value，並由 {@link DeadLetterPublishingRecoverer} 附上例外資訊 header，
 * 修正後可重新送回 draw.completed（draw_id 去重讓重送安全）。成功處理（或送進 DLT）的 offset 才會 commit。
 */
@Configuration(proxyBeanMethods = false)
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    @Bean
    NewTopic drawCompletedDeadLetterTopic(RecordConsumerProperties properties) {
        return TopicBuilder.name(properties.deadLetterTopic())
                .partitions(properties.deadLetterPartitions())
                .replicas(properties.deadLetterReplicas())
                .build();
    }

    @Bean
    DefaultErrorHandler drawCompletedErrorHandler(KafkaOperations<String, String> kafkaOperations,
                                                  RecordConsumerProperties properties) {
        // partition -1：交給 producer 依 key 分配，dead-letter topic 的 partition 數不必和來源相同
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations,
                (record, exception) -> {
                    log.error("Giving up on draw.completed record {}-{}@{} (key {}); sending it to {}",
                            record.topic(), record.partition(), record.offset(), record.key(),
                            properties.deadLetterTopic(), exception);
                    return new TopicPartition(properties.deadLetterTopic(), -1);
                });

        ExponentialBackOffWithMaxRetries bounded = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        bounded.setInitialInterval(properties.retryInitialInterval().toMillis());
        bounded.setMultiplier(2.0);
        bounded.setMaxInterval(properties.retryMaxInterval().toMillis());

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, bounded);
        handler.addNotRetryableExceptions(InvalidDrawEventException.class);
        handler.setBackOffFunction((record, exception) ->
                isTransient(exception) ? unboundedBackOff(properties) : null);
        return handler;
    }

    private static BackOff unboundedBackOff(RecordConsumerProperties properties) {
        ExponentialBackOff backOff = new ExponentialBackOff(properties.retryInitialInterval().toMillis(), 2.0);
        backOff.setMaxInterval(properties.retryMaxInterval().toMillis());
        return backOff;
    }
}
