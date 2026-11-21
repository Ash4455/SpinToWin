package com.spintowin.draw.config;

import com.spintowin.common.event.DrawCompletedEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Kafka topic {@value DrawCompletedEvent#TOPIC}：啟動時由 Boot 的 KafkaAdmin 建立（已存在則不變；
 * partition 數只會增加不會減少）。message key 為 campaignId，partition 數決定消費端最多能平行幾個 consumer。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class KafkaTopicConfig {

    @Bean
    NewTopic drawCompletedNewTopic(@Value("${outbox.topic.partitions:6}") int partitions,
                                  @Value("${outbox.topic.replicas:1}") int replicas) {
        return TopicBuilder.name(DrawCompletedEvent.TOPIC).partitions(partitions).replicas(replicas).build();
    }
}
