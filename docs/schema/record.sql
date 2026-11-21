-- =====================================================================
-- record schema（record-service 擁有）
-- 實際由 Flyway 建立：backend/record-service/src/main/resources/db/migration
--   V1__create_record_schema.sql  draw_record
-- MySQL 8.4，InnoDB，utf8mb4。時間一律為 UTC 的 DATETIME(6)。
-- campaign_id、prize_id 參照 campaign schema，但只存 id，不建跨 schema 外鍵。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS record CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE record;

-- ---------------------------------------------------------------------
-- DDL
-- ---------------------------------------------------------------------

-- 每個 Draw Result 一筆紀錄，由 draw.completed 事件寫入。
-- 事件是 at-least-once：draw_id UNIQUE 讓重複投遞成為 no-op。
-- prize_id / prize_name 皆為 NULL = 銘謝惠顧；prize_name 是抽中當下的名稱（來自事件）。
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

-- ---------------------------------------------------------------------
-- DML
-- record schema 沒有 Flyway 範例資料：紀錄只由抽獎事件產生。
-- 以下示範一次 3 連抽（1 次中「免運券」、2 次銘謝惠顧）寫入的資料與常用查詢。
-- ---------------------------------------------------------------------

-- 消費端的寫入：重複投遞的事件因 draw_id 已存在而不變（no-op）
INSERT INTO draw_record (draw_id, request_id, draw_seq, campaign_id, user_id, prize_id, prize_name, drawn_at)
VALUES ('f5a8c065-6a51-470f-9556-6706e1e8bd09-1', 'f5a8c065-6a51-470f-9556-6706e1e8bd09', 1, 1,
        '0c3f8f6e-2b7a-4f0e-9a51-6d1c2b9e4f10', NULL, NULL, '2026-11-21 03:23:33.803000'),
       ('f5a8c065-6a51-470f-9556-6706e1e8bd09-2', 'f5a8c065-6a51-470f-9556-6706e1e8bd09', 2, 1,
        '0c3f8f6e-2b7a-4f0e-9a51-6d1c2b9e4f10', 3, '免運券', '2026-11-21 03:23:33.803000'),
       ('f5a8c065-6a51-470f-9556-6706e1e8bd09-3', 'f5a8c065-6a51-470f-9556-6706e1e8bd09', 3, 1,
        '0c3f8f6e-2b7a-4f0e-9a51-6d1c2b9e4f10', NULL, NULL, '2026-11-21 03:23:33.803000')
ON DUPLICATE KEY UPDATE draw_id = draw_id;

-- 我的紀錄（GET /campaigns/{id}/draws/me），走 idx_draw_record_user_history
SELECT draw_id, request_id, prize_id, prize_name, drawn_at
  FROM draw_record
 WHERE campaign_id = 1 AND user_id = '0c3f8f6e-2b7a-4f0e-9a51-6d1c2b9e4f10'
 ORDER BY drawn_at DESC, request_id DESC, draw_seq DESC
 LIMIT 20 OFFSET 0;

-- 統計（GET /admin/campaigns/{id}/stats），走 idx_draw_record_campaign_prize；prize_id IS NULL 為銘謝惠顧
SELECT prize_id, prize_name, COUNT(*) AS drawn_count
  FROM draw_record
 WHERE campaign_id = 1
 GROUP BY prize_id, prize_name;
