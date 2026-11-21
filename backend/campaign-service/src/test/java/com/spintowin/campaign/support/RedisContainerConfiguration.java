package com.spintowin.campaign.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真 Redis（Testcontainers）。以 bean 方式宣告，讓快取的 Spring context 共用同一個 container。
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfiguration {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);
    }
}
