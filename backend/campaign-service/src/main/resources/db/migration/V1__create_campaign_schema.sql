-- campaign schema：Campaign 與其 Prize。
-- Win Probability 以整數 ppm（百萬分率）儲存：1,000,000 ppm = 100%。
-- 同一 Campaign 所有 Prize 的 ppm 總和 <= 1,000,000，剩餘即銘謝惠顧。

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
