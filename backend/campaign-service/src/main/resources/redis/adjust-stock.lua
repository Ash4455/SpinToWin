-- 以增減量原子地調整一個 Prize 的剩餘 Stock，結果不可為負數。
-- 與抽獎 Lua 的扣減在 Redis 內序列化，因此不會蓋掉已被抽走的數量。
--
-- KEYS[1] = stock (HASH)
-- ARGV[1] = prizeId   ARGV[2] = delta（可為負）
-- 回傳 {結果, 剩餘 Stock}：
--   { 1, 調整後的剩餘量 }  已套用
--   {-1, 目前的剩餘量 }    會變成負數，未套用
--   { 0, 0 }              Prize 不在 Redis 中，未套用
local current = redis.call('HGET', KEYS[1], ARGV[1])
if not current then
    return {0, 0}
end

local delta = tonumber(ARGV[2])
if tonumber(current) + delta < 0 then
    return {-1, tonumber(current)}
end
return {1, redis.call('HINCRBY', KEYS[1], ARGV[1], delta)}
