package com.spintowin.draw.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 真 Kafka（Testcontainers，單節點 KRaft）。以 bean 方式宣告，讓快取的 Spring context 共用同一個 container；
 * {@link DrawCompletedTopic} 從 topic 讀回 relay 送出的事件。
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaContainerConfiguration {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.4.0");
    }

    @Bean(destroyMethod = "close")
    DrawCompletedTopic drawCompletedTopic(KafkaContainer kafka) {
        return new DrawCompletedTopic(kafka.getBootstrapServers());
    }
}
