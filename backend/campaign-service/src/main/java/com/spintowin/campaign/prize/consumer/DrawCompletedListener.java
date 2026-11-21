package com.spintowin.campaign.prize.consumer;

import com.spintowin.campaign.prize.service.RemainingStockService;
import com.spintowin.common.event.DrawCompletedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 以 campaign-service 自己的 consumer group（{@code spring.kafka.consumer.group-id}）消費 draw.completed。
 * 事件在 {@link RemainingStockService#apply} 的交易 commit 後才回傳，offset 也在那之後才提交（ack-mode RECORD），
 * 因此崩潰只會造成重送，由已處理事件表去重。失敗處理見 {@code config.KafkaConsumerConfig}。
 */
@Component
public class DrawCompletedListener {

    private final RemainingStockService remainingStockService;

    public DrawCompletedListener(RemainingStockService remainingStockService) {
        this.remainingStockService = remainingStockService;
    }

    @KafkaListener(id = "remaining-stock", idIsGroup = false, topics = DrawCompletedEvent.TOPIC)
    void onDrawCompleted(DrawCompletedEvent event) {
        remainingStockService.apply(event);
    }
}
