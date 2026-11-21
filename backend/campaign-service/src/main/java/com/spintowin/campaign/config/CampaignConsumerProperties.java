package com.spintowin.campaign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * draw.completed consumer（扣減 MySQL remaining_stock）的錯誤處理（{@code campaign.consumer.*}，對應環境變數見 application.yml）。
 *
 * @param maxRetries            非暫時性錯誤的重試次數，用完後送進 dead-letter topic
 * @param retryInitialInterval  第一次重試前的等待時間（之後每次加倍）
 * @param retryMaxInterval      重試間隔上限；資料庫暫時無法使用時以此間隔無限重試
 * @param deadLetterTopic       無法處理的事件送往的 topic（原始內容，並附上例外資訊 header）
 * @param deadLetterPartitions  dead-letter topic 的 partition 數
 * @param deadLetterReplicas    dead-letter topic 的 replication factor
 */
@ConfigurationProperties("campaign.consumer")
public record CampaignConsumerProperties(int maxRetries, Duration retryInitialInterval, Duration retryMaxInterval,
                                         String deadLetterTopic, int deadLetterPartitions, int deadLetterReplicas) {
}
