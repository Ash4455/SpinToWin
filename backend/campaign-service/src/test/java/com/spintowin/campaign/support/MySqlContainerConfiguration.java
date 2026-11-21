package com.spintowin.campaign.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 真 MySQL（Testcontainers）。以 bean 方式宣告，讓快取的 Spring context 共用同一個 container。
 */
@TestConfiguration(proxyBeanMethods = false)
public class MySqlContainerConfiguration {

    @Bean
    @ServiceConnection
    MySQLContainer mySqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4.13"))
                .withDatabaseName("campaign")
                // 鎖等待 2 秒就逾時（預設 50 秒），讓「MySQL 暫時失敗 → consumer 重試」的測試在數秒內發生
                .withCommand("--innodb-lock-wait-timeout=2");
    }
}
