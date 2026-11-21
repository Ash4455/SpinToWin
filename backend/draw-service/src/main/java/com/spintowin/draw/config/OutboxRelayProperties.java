package com.spintowin.draw.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * outbox relay 的設定（{@code outbox.relay.*}，對應環境變數見 application.yml）。
 *
 * @param enabled              false = 這個實例不轉送（抽獎仍會寫 outbox，由其他實例轉送）
 * @param pollInterval         兩輪讀取之間的間隔；也是新事件送到 Kafka 的大約延遲
 * @param batchSize            每個 Campaign 每次 XREADGROUP / XCLAIM 最多取幾筆
 * @param claimInterval        多久檢查一次逾時未 ack 的事件
 * @param claimMinIdle         未 ack 超過這麼久的事件會被接手重送（崩潰的實例或送出失敗）。
 *                             應大於 {@code sendTimeout}，否則仍在送的事件會被其他實例重送（只會重複，不會遺失）
 * @param sendTimeout          等待 Kafka 確認（acks=all）的上限；逾時視為失敗，事件留在 pending 等待重送
 * @param staleConsumerIdle    consumer group 中閒置超過這麼久、且沒有 pending 的 consumer 會被刪除
 *                             （每次重啟都用新的 consumer 名稱，舊名稱不會再被使用）
 * @param consumerName         這個實例在 consumer group 中的名稱；空白 = hostname + 隨機字尾
 */
@ConfigurationProperties("outbox.relay")
public record OutboxRelayProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("100ms") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration claimInterval,
        @DefaultValue("30s") Duration claimMinIdle,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("1h") Duration staleConsumerIdle,
        @Nullable String consumerName) {
}
