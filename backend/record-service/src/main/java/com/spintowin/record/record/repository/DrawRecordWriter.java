package com.spintowin.record.record.repository;

import com.spintowin.common.event.DrawCompletedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 把一筆 draw.completed 事件的每個結果寫成一列 {@code draw_record}。
 * <p>
 * 以 {@code INSERT ... ON DUPLICATE KEY UPDATE draw_id = draw_id} 逐列 upsert：已存在的 draw_id 不變（no-op），
 * 其餘照常寫入。所以重複投遞的事件不會產生重複紀錄，部分結果已存在的事件也只補寫缺少的結果，而不是整筆失敗。
 * 其他錯誤（例如欄位超長）仍會丟出例外。
 */
@Repository
public class DrawRecordWriter {

    private static final String INSERT = """
            INSERT INTO draw_record (draw_id, request_id, draw_seq, campaign_id, user_id, prize_id, prize_name, drawn_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE draw_id = draw_id
            """;

    private final JdbcTemplate jdbc;

    public DrawRecordWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 寫入事件中尚未存在的結果；須在交易中呼叫。依抽獎順序寫入，並行處理同一事件時鎖的取得順序一致。 */
    public void insertMissing(DrawCompletedEvent event) {
        // DATETIME 以 UTC 儲存（與 hibernate.jdbc.time_zone=UTC 一致）；LocalDateTime 不受 JVM 時區影響
        LocalDateTime drawnAt = LocalDateTime.ofInstant(event.requestedAt(), ZoneOffset.UTC);
        List<Object[]> rows = new ArrayList<>(event.results().size());
        for (int i = 0; i < event.results().size(); i++) {
            DrawCompletedEvent.Result result = event.results().get(i);
            rows.add(new Object[] {result.drawId(), event.requestId(), i + 1, event.campaignId(), event.userId(),
                    result.prizeId(), result.prizeName(), drawnAt});
        }
        jdbc.batchUpdate(INSERT, rows);
    }
}
