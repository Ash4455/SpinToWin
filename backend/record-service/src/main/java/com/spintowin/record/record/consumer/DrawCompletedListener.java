package com.spintowin.record.record.consumer;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.record.record.service.DrawRecordService;
import com.spintowin.common.event.InvalidDrawEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 消費 {@value DrawCompletedEvent#TOPIC}。每筆事件在自己的交易中寫入；listener 回傳（交易已 commit）之後
 * container 才 commit offset，因此崩潰只會造成重送，不會遺失（重送由 draw_id 去重）。
 * <p>
 * value 以字串接收再自行解析：格式錯誤的事件因此能原封不動送進 dead-letter topic（見 {@code KafkaConsumerConfig}）。
 */
@Component
public class DrawCompletedListener {

    private final JsonMapper jsonMapper;
    private final DrawRecordService drawRecordService;

    public DrawCompletedListener(JsonMapper jsonMapper, DrawRecordService drawRecordService) {
        this.jsonMapper = jsonMapper;
        this.drawRecordService = drawRecordService;
    }

    // idIsGroup = false：consumer group 取自 spring.kafka.consumer.group-id（KAFKA_CONSUMER_GROUP），而不是 listener id
    @KafkaListener(id = "draw-record-consumer", idIsGroup = false, topics = DrawCompletedEvent.TOPIC)
    void onDrawCompleted(ConsumerRecord<String, String> record) {
        drawRecordService.record(parse(record.value()));
    }

    private DrawCompletedEvent parse(String json) {
        if (json == null) {
            throw new InvalidDrawEventException("Event value is null");
        }
        try {
            return jsonMapper.readValue(json, DrawCompletedEvent.class);
        } catch (JacksonException e) {
            throw new InvalidDrawEventException("Event is not a valid draw.completed JSON: " + e.getOriginalMessage(), e);
        }
    }
}
