-- 一次抽獎請求（單抽或 Multi-Draw）的原子判定。
-- Redis 單執行緒執行整個 script，因此「檢查 → 判定 → 扣減」之間不會插入其他請求：絕不超抽、絕不超次數。
-- 所有 key 都帶同一個 {campaignId} hash tag（同一 slot，可換成 Redis Cluster）；全域的 campaigns:live 不在這裡碰。
--
-- KEYS[1] = meta        (HASH)   status / startAt / endAt（epoch 毫秒）/ drawQuota
-- KEYS[2] = intervals   (ZSET)   member = prizeId，score = ppm 區間上界（不含），依 Prize 順序累加
-- KEYS[3] = prize-names (HASH)   prizeId → 名稱
-- KEYS[4] = stock       (HASH)   prizeId → 剩餘 Stock
-- KEYS[5] = used        (STRING) 此使用者已用的 Draw 次數
-- KEYS[6] = idempotency (STRING) 此使用者這個 Idempotency-Key 的結果（JSON）
-- KEYS[7] = outbox      (STREAM) draw.completed 事件（field 'event' = JSON，由 draw-service 的 outbox relay 轉送 Kafka）
--
-- ARGV[1] = requestId（Java 產生的 UUID；每一抽的 drawId = requestId .. '-' .. 序號）
-- ARGV[2] = Idempotency 結果的 TTL（秒）
-- ARGV[3] = count（1..10）
-- ARGV[4 .. 3+count] = 每一抽的隨機落點 r ∈ [0, 1000000)
-- ARGV[4+count] = userId（JWT sub）
-- ARGV[5+count] = campaignId
--
-- 回傳（第一個元素是狀態）：
--   { 'DRAWN', json }          判定完成；json 同時存進 KEYS[6]
--   { 'REPLAYED', json }       相同 Idempotency-Key 的先前結果，這次沒有任何扣減
--   { 'NOT_LOADED' }           meta 不存在（Campaign 未上線或不存在）
--   { 'NOT_ACTIVE' }           status 不是 ACTIVE
--   { 'NOT_STARTED' }/{ 'ENDED' } 現在不在 [startAt, endAt] 內
--   { 'QUOTA_EXCEEDED', remaining } 剩餘次數 < count，整筆拒絕
-- 被拒絕的請求不會寫任何 key（也不記 Idempotency），同一個 key 之後仍可成功。
--
-- json = {"requestId":..., "drawnAt": epoch 毫秒, "remainingDraws": n,
--         "results":[{"drawId":..., "prizeId": 1, "prizeName": "..."}, {"drawId":...}]}  （銘謝惠顧沒有 prizeId / prizeName）
--
-- outbox 事件（XADD KEYS[7] * event <json>，只在 DRAWN 時寫入）：
--   {"campaignId":..., "userId":..., "requestId":..., "requestedAt": epoch 毫秒, "results": 同上}

local KEY_META, KEY_INTERVALS, KEY_NAMES, KEY_STOCK, KEY_USED, KEY_IDEMPOTENCY, KEY_OUTBOX =
    KEYS[1], KEYS[2], KEYS[3], KEYS[4], KEYS[5], KEYS[6], KEYS[7]
local requestId = ARGV[1]
local ttlSeconds = ARGV[2]
local count = tonumber(ARGV[3])
local userId = ARGV[4 + count]
local campaignId = tonumber(ARGV[5 + count])

-- 1. 冪等：已有結果就原樣回傳，不做任何檢查與扣減（即使活動之後已結束）
local previous = redis.call('GET', KEY_IDEMPOTENCY)
if previous then
    return { 'REPLAYED', previous }
end

-- 2. ACTIVE 與時間窗
local meta = redis.call('HMGET', KEY_META, 'status', 'startAt', 'endAt', 'drawQuota')
if not meta[1] then
    return { 'NOT_LOADED' }
end
if meta[1] ~= 'ACTIVE' then
    return { 'NOT_ACTIVE' }
end
-- 以 Redis 的 TIME 而不是呼叫端傳入的時間判斷：draw-service 會 --scale 成多個實例，各自的時鐘可能有偏差，
-- 而 Redis 是所有實例共用的唯一時間來源，邊界時刻的判定才會一致。Redis 7+ 的 script 一律以 effects 複寫，
-- 在 script 中呼叫 TIME 後再寫入是安全的。
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if now < tonumber(meta[2]) then
    return { 'NOT_STARTED' }
end
if now > tonumber(meta[3]) then
    return { 'ENDED' }
end

-- 3. 剩餘次數不足 count：整筆拒絕，什麼都不扣
local quota = tonumber(meta[4])
local used = tonumber(redis.call('GET', KEY_USED) or '0')
local remaining = math.max(quota - used, 0)
if remaining < count then
    return { 'QUOTA_EXCEEDED', remaining }
end

-- 4. 每一抽獨立判定：找第一個上界 > r 的區間；落在所有區間之外即銘謝惠顧。
--    落點 Prize 的 Stock 為 0 時改判銘謝惠顧，否則扣 1。同一次 Multi-Draw 可重複抽中同一個 Prize。
-- 6.（一部分）每一抽的 drawId 在這裡產生
local results = {}
for i = 1, count do
    local r = ARGV[3 + i]
    local hit = redis.call('ZRANGE', KEY_INTERVALS, '(' .. r, '+inf', 'BYSCORE', 'LIMIT', 0, 1)
    local prizeId = hit[1]
    local result = { drawId = requestId .. '-' .. i }
    if prizeId then
        local stock = tonumber(redis.call('HGET', KEY_STOCK, prizeId) or '0')
        if stock > 0 then
            redis.call('HINCRBY', KEY_STOCK, prizeId, -1)
            result.prizeId = tonumber(prizeId)
            result.prizeName = redis.call('HGET', KEY_NAMES, prizeId)
        end
    end
    results[i] = result
end

-- 5. 扣已用次數
redis.call('INCRBY', KEY_USED, count)

local payload = cjson.encode({
    requestId = requestId, drawnAt = now, remainingDraws = remaining - count, results = results })

-- 6. outbox：和扣減在同一個原子操作中寫入事件，因此「扣了 Stock 卻沒有事件」不會發生。
--    只有新判定會到這裡；相同 Idempotency-Key 的重送在步驟 1 就回傳 REPLAYED，不會產生第二筆事件。
--    不在這裡 MAXLEN 修剪：尚未送出的事件不能被丟掉，relay 在 Kafka 確認後以 XACKDEL 逐筆刪除。
redis.call('XADD', KEY_OUTBOX, '*', 'event', cjson.encode({
    campaignId = campaignId, userId = userId, requestId = requestId, requestedAt = now, results = results }))

-- 7. 存 Idempotency 結果並回傳
redis.call('SET', KEY_IDEMPOTENCY, payload, 'EX', ttlSeconds)
return { 'DRAWN', payload }
