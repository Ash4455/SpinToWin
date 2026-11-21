package com.spintowin.record.support;

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
                .withDatabaseName("record");
    }
}
