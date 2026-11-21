-- 載入一個 Campaign 的 live state（設定、ppm 區間、Prize 名稱與 Stock），整批原子寫入。
-- 不碰使用者已用次數、Idempotency 結果與 outbox stream。
--
-- KEYS[1] = meta (HASH)   KEYS[2] = intervals (ZSET)   KEYS[3] = prize-names (HASH)   KEYS[4] = stock (HASH)
-- ARGV[1] = 'replace'：覆寫既有內容；'if-absent'：meta 已存在就什麼都不做
-- ARGV[2] = meta 參數個數 n，ARGV[3 .. 2+n] = meta 的 field, value, field, value ...
-- 之後每 4 個一組：prizeId, name, 區間上界（空字串 = ppm 為 0，不放進 intervals）, stock
-- 回傳 1 = 已寫入，0 = 因 'if-absent' 而略過
if ARGV[1] == 'if-absent' and redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end

local metaLen = tonumber(ARGV[2])
redis.call('DEL', KEYS[1], KEYS[2], KEYS[3], KEYS[4])
redis.call('HSET', KEYS[1], unpack(ARGV, 3, 2 + metaLen))

for i = 3 + metaLen, #ARGV, 4 do
    local prizeId = ARGV[i]
    redis.call('HSET', KEYS[3], prizeId, ARGV[i + 1])
    if ARGV[i + 2] ~= '' then
        redis.call('ZADD', KEYS[2], ARGV[i + 2], prizeId)
    end
    redis.call('HSET', KEYS[4], prizeId, ARGV[i + 3])
end
return 1
