-- 更新 live state 的 Campaign Status；Campaign 不在 Redis 中時什麼都不做（不建立殘缺的 meta）。
--
-- KEYS[1] = meta (HASH)
-- ARGV[1] = status 的 field 名稱   ARGV[2] = 新的 status
-- 回傳 1 = 已更新，0 = Campaign 不在 Redis 中
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 0
end
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
return 1
