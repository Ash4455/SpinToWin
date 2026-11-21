-- 上線期間修改 Prize 名稱或 Win Probability：整批重寫 ppm 區間與 Prize 名稱，不碰 Stock。
--
-- KEYS[1] = meta (HASH)   KEYS[2] = intervals (ZSET)   KEYS[3] = prize-names (HASH)
-- ARGV 每 3 個一組：prizeId, name, 區間上界（空字串 = ppm 為 0，不放進 intervals）
-- 回傳 1 = 已同步，0 = Campaign 不在 Redis 中（什麼都不做）
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end

redis.call('DEL', KEYS[2], KEYS[3])
for i = 1, #ARGV, 3 do
    local prizeId = ARGV[i]
    redis.call('HSET', KEYS[3], prizeId, ARGV[i + 1])
    if ARGV[i + 2] ~= '' then
        redis.call('ZADD', KEYS[2], ARGV[i + 2], prizeId)
    end
end
return 1
