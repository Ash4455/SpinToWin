-- =====================================================================
-- campaign schema（campaign-service 擁有）
-- 實際由 Flyway 建立：backend/campaign-service/src/main/resources/db/migration
--   V1__create_campaign_schema.sql      campaign、prize
--   V2__seed_campaign.sql               範例資料
--   V3__create_processed_draw_event.sql processed_draw_event
-- MySQL 8.4，InnoDB，utf8mb4。時間一律為 UTC 的 DATETIME(6)。
-- =====================================================================

CREATE DATABASE IF NOT EXISTS campaign CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE campaign;

-- ---------------------------------------------------------------------
-- DDL
-- ---------------------------------------------------------------------

-- Campaign（抽獎活動）：時間窗、每人抽獎次數上限、狀態。
CREATE TABLE campaign (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,
    start_at    DATETIME(6)  NOT NULL COMMENT '開放抽獎起始時間（UTC）',
    end_at      DATETIME(6)  NOT NULL COMMENT '開放抽獎結束時間（UTC）',
    draw_quota  INT          NOT NULL COMMENT '每位使用者可抽次數上限',
    status      VARCHAR(16)  NOT NULL COMMENT 'DRAFT / ACTIVE / ENDED',
    created_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT ck_campaign_time_window CHECK (end_at > start_at),
    CONSTRAINT ck_campaign_draw_quota CHECK (draw_quota > 0),
    CONSTRAINT ck_campaign_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Prize（獎品）：Win Probability 以整數 ppm（百萬分率）儲存，1,000,000 ppm = 100%。
-- 同一 Campaign 所有 Prize 的 ppm 總和 <= 1,000,000（由應用程式驗證），剩餘即銘謝惠顧。
-- Prize 順序 = id 由小到大，也是抽獎時機率區間的累加順序。
CREATE TABLE prize (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    campaign_id      BIGINT       NOT NULL,
    name             VARCHAR(100) NOT NULL,
    probability_ppm  INT          NOT NULL COMMENT 'Win Probability，單位 ppm（1,000,000 = 100%）',
    total_stock      INT          NOT NULL COMMENT '設定的總 Stock',
    remaining_stock  INT          NOT NULL COMMENT '剩餘 Stock（由 draw.completed 事件最終一致地遞減）',
    created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_prize_campaign (campaign_id),
    CONSTRAINT fk_prize_campaign FOREIGN KEY (campaign_id) REFERENCES campaign (id),
    CONSTRAINT ck_prize_probability CHECK (probability_ppm BETWEEN 0 AND 1000000),
    CONSTRAINT ck_prize_total_stock CHECK (total_stock >= 0),
    CONSTRAINT ck_prize_remaining_stock CHECK (remaining_stock >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 已處理事件：campaign-service 消費 draw.completed 的去重紀錄。
-- 事件是 at-least-once，同一筆可能送到多次。一筆事件 = 一次抽獎請求（1..10 抽），
-- 插入這一列與扣減 remaining_stock 在同一個交易中；插入不到（已存在）即代表處理過，不再扣減。
CREATE TABLE processed_draw_event (
    request_id    VARCHAR(64) NOT NULL COMMENT 'draw.completed 的 requestId',
    campaign_id   BIGINT      NOT NULL,
    processed_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id),
    KEY idx_processed_draw_event_campaign (campaign_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------
-- DML：範例資料（Flyway V2）
-- 一個 ACTIVE 的 Campaign 與 3 個 Prize。
-- 機率：1% + 9.5% + 30% = 40.5%，銘謝惠顧 59.5%。
-- ---------------------------------------------------------------------

INSERT INTO campaign (id, name, start_at, end_at, draw_quota, status)
VALUES (1, '週年慶轉盤抽獎', '2026-01-01 00:00:00', '2030-12-31 23:59:59', 10, 'ACTIVE');

INSERT INTO prize (id, campaign_id, name, probability_ppm, total_stock, remaining_stock)
VALUES (1, 1, '藍牙耳機', 10000, 10, 10),
       (2, 1, '100 元購物金', 95000, 200, 200),
       (3, 1, '免運券', 300000, 1000, 1000);

-- ---------------------------------------------------------------------
-- 應用程式使用的主要寫入（參考，非 Flyway 內容）
-- ---------------------------------------------------------------------

-- 消費一筆 draw.completed（同一個交易）：
--   1) 去重：影響 0 列 = 已處理過，整筆略過
--      INSERT IGNORE INTO processed_draw_event (request_id, campaign_id) VALUES (?, ?);
--   2) 依 prize id 由小到大（固定的鎖順序）扣減，扣完不會小於 0 才生效
--      UPDATE prize SET remaining_stock = remaining_stock - ?
--       WHERE id = ? AND campaign_id = ? AND remaining_stock >= ?;

-- ADMIN 增減庫存：先 SELECT ... FOR UPDATE 鎖住 campaign 與 prize 列，再同時調整總量與剩餘量
--   UPDATE prize SET total_stock = total_stock + ?, remaining_stock = remaining_stock + ? WHERE id = ?;
