package com.spintowin.record.record.service;

import com.spintowin.common.event.DrawCompletedEvent;
import com.spintowin.common.event.InvalidDrawEventException;
import com.spintowin.record.record.dto.DrawRecordPageResponse;
import com.spintowin.record.record.repository.DrawRecordRepository;
import com.spintowin.record.record.repository.DrawRecordWriter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/** 把 draw.completed 事件落庫。可安全重複呼叫：同一 drawId 只會有一筆紀錄。 */
@Service
@Transactional(readOnly = true)
public class DrawRecordService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("drawnAt"), Sort.Order.desc("requestId"),
            Sort.Order.desc("drawSeq"));

    private final DrawRecordWriter writer;
    private final DrawRecordRepository repository;

    public DrawRecordService(DrawRecordWriter writer, DrawRecordRepository repository) {
        this.writer = writer;
        this.repository = repository;
    }

    /**
     * 寫入事件中每個尚未落庫的 Draw Result。
     *
     * @throws InvalidDrawEventException 事件缺少必要欄位或內容矛盾（重試不會成功）
     */
    @Transactional
    public void record(DrawCompletedEvent event) {
        requireValid(event);
        writer.insertMissing(event);
    }

    /** 使用者在某 Campaign 的紀錄，新到舊：抽獎時間、所屬請求、請求內順序皆由大到小。 */
    public DrawRecordPageResponse findMine(long campaignId, String userId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, NEWEST_FIRST);
        return DrawRecordPageResponse.from(repository.findByCampaignIdAndUserId(campaignId, userId, pageRequest));
    }

    private static void requireValid(DrawCompletedEvent event) {
        if (event.campaignId() <= 0) {
            throw new InvalidDrawEventException("campaignId must be positive: " + event.campaignId());
        }
        requireText(event.userId(), "userId");
        requireText(event.requestId(), "requestId");
        if (event.requestedAt() == null) {
            throw new InvalidDrawEventException("requestedAt is missing");
        }
        if (event.results() == null || event.results().isEmpty()) {
            throw new InvalidDrawEventException("results is empty");
        }
        Set<String> drawIds = new HashSet<>();
        for (DrawCompletedEvent.Result result : event.results()) {
            if (result == null) {
                throw new InvalidDrawEventException("results contains null");
            }
            requireText(result.drawId(), "drawId");
            if (!drawIds.add(result.drawId())) {
                throw new InvalidDrawEventException("drawId appears twice: " + result.drawId());
            }
            if ((result.prizeId() == null) != (result.prizeName() == null)) {
                throw new InvalidDrawEventException(
                        "prizeId and prizeName must both be present or both be null (drawId " + result.drawId() + ")");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidDrawEventException(field + " is missing");
        }
    }
}
