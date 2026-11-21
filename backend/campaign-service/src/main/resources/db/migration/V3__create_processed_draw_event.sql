-- 已處理事件表：campaign-service 消費 draw.completed 的去重紀錄。
-- 事件是 at-least-once，同一筆事件可能送到多次。一筆事件 = 一次抽獎請求（1..10 抽），
-- 其所有 Draw 一起投遞、在同一個交易中扣減，因此以 request_id 為去重單位：
-- 插入這一列與扣減 remaining_stock 在同一個交易中，插入失敗（已存在）即代表已處理過，不再扣減。

CREATE TABLE processed_draw_event (
    request_id    VARCHAR(64) NOT NULL COMMENT 'draw.completed 的 requestId',
    campaign_id   BIGINT      NOT NULL,
    processed_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_processed_draw_event_campaign (campaign_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
