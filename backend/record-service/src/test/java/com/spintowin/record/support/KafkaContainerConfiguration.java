package com.spintowin.record.support;

import com.spintowin.common.event.DrawCompletedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 真 Kafka（Testcontainers，單節點 KRaft）。以 bean 方式宣告，讓快取的 Spring context 共用同一個 container。
 * draw-service 不在測試中執行，所以由這裡以與正式環境相同的 6 個 partition 建立 {@code draw.completed}。
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.4.0");
    }

    @Bean
    NewTopic drawCompletedTestTopic() {
        return TopicBuilder.name(DrawCompletedEvent.TOPIC).partitions(6).replicas(1).build();
    }

    @Bean(destroyMethod = "close")
    DrawCompletedTopic drawCompletedTopic(KafkaContainer kafka,
                                          @Value("${record.consumer.dead-letter-topic}") String deadLetterTopic) {
        return new DrawCompletedTopic(kafka.getBootstrapServers(), deadLetterTopic);
    }
}
