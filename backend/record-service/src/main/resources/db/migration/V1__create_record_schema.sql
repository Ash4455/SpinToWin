-- record schema：每個 Draw Result 一筆紀錄，由 draw.completed 事件寫入。
-- 事件是 at-least-once：draw_id UNIQUE 讓重複投遞成為 no-op（INSERT ... ON DUPLICATE KEY UPDATE）。
-- prize_id / prize_name 皆為 NULL = 銘謝惠顧；prize_name 是抽中當下的名稱（來自事件，不查 campaign-service）。

CREATE TABLE draw_record (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    draw_id      VARCHAR(64)  NOT NULL COMMENT '這一抽的唯一識別（<requestId>-<序號>），去重用',
    request_id   VARCHAR(64)  NOT NULL COMMENT '所屬的抽獎請求（單抽或 Multi-Draw）',
    draw_seq     INT          NOT NULL COMMENT '在所屬請求中的順序，從 1 開始',
    campaign_id  BIGINT       NOT NULL,
    user_id      VARCHAR(64)  NOT NULL COMMENT '抽獎的使用者（JWT sub）',
    prize_id     BIGINT       NULL     COMMENT '抽中的 Prize；NULL = 銘謝惠顧',
    prize_name   VARCHAR(100) NULL     COMMENT '抽中當下的 Prize 名稱；NULL = 銘謝惠顧',
    drawn_at     DATETIME(6)  NOT NULL COMMENT 'Redis 判定這次請求的時間（UTC）',
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_draw_record_draw_id (draw_id),
    -- 我的紀錄：WHERE campaign_id, user_id ORDER BY drawn_at DESC, request_id DESC, draw_seq DESC
    KEY idx_draw_record_user_history (campaign_id, user_id, drawn_at, request_id, draw_seq),
    -- 統計：WHERE campaign_id GROUP BY prize_id
    KEY idx_draw_record_campaign_prize (campaign_id, prize_id),
    CONSTRAINT ck_draw_record_draw_seq CHECK (draw_seq >= 1),
    CONSTRAINT ck_draw_record_prize CHECK ((prize_id IS NULL) = (prize_name IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
