package com.spintowin.campaign.support;

import com.spintowin.common.event.DrawCompletedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * 真 Kafka（Testcontainers，單節點 KRaft）。以 bean 方式宣告，讓快取的 Spring context 共用同一個 container。
 * <p>
 * 正式環境的 topic {@value DrawCompletedEvent#TOPIC} 由 draw-service 建立；測試中沒有 draw-service，
 * 因此在這裡以相同的 partition 數建立。{@link DrawCompletedEvents} 以事件合約的 JSON 發送事件。
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
        return new NewTopic(DrawCompletedEvent.TOPIC, 6, (short) 1);
    }

    @Bean(destroyMethod = "close")
    DrawCompletedEvents drawCompletedEvents(KafkaContainer kafka) {
        return new DrawCompletedEvents(kafka.getBootstrapServers());
    }
}
