package com.spintowin.record.support;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 讀 record schema 的 {@code draw_record}（Seam 3 的觀察點：事件進來後持久化的 DB 狀態）。
 */
@TestComponent
public class DrawRecordTable {

    private final JdbcClient jdbc;

    public DrawRecordTable(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 一個 Campaign 的所有紀錄，依 draw_id 排序。 */
    public List<Row> rows(long campaignId) {
        return jdbc.sql("""
                        SELECT draw_id, request_id, user_id, prize_id, prize_name, drawn_at
                        FROM draw_record WHERE campaign_id = ? ORDER BY draw_id
                        """)
                .param(campaignId)
                .query((rs, n) -> new Row(rs.getString("draw_id"), rs.getString("request_id"),
                        rs.getString("user_id"), rs.getObject("prize_id", Long.class), rs.getString("prize_name"),
                        rs.getObject("drawn_at", java.time.LocalDateTime.class).toInstant(ZoneOffset.UTC)))
                .list();
    }

    public record Row(String drawId, String requestId, String userId, @Nullable Long prizeId,
                      @Nullable String prizeName, Instant drawnAt) {
    }
}
