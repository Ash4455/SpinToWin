-- 範例 DML：一個 ACTIVE 的 Campaign 與 3 個 Prize。
-- 機率：1% + 9.5% + 30% = 40.5%，銘謝惠顧 59.5%。

INSERT INTO campaign (id, name, start_at, end_at, draw_quota, status)
VALUES (1, '週年慶轉盤抽獎', '2026-01-01 00:00:00', '2030-12-31 23:59:59', 10, 'ACTIVE');

INSERT INTO prize (id, campaign_id, name, probability_ppm, total_stock, remaining_stock)
VALUES (1, 1, '藍牙耳機', 10000, 10, 10),
       (2, 1, '100 元購物金', 95000, 200, 200),
       (3, 1, '免運券', 300000, 1000, 1000);
