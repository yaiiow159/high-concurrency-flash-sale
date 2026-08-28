--[[
  令牌桶限流（Token Bucket）。

  相較固定視窗計數，令牌桶容許短時突發（burst）而不會在視窗邊界出現流量翻倍。
  秒殺開賣的瞬間本來就是突發流量，用固定視窗會誤殺大量正常使用者。

  以「惰性補充」實作：不需要背景執行緒定時加令牌，
  而是在每次請求時依經過的時間換算應補多少，成本 O(1) 且無額外執行緒。

  KEYS[1]  桶的鍵         seckill:rl:<scope>:<id>

  ARGV[1]  桶容量（可累積的最大令牌數，決定容許的突發量）
  ARGV[2]  每秒補充速率
  ARGV[3]  本次請求消耗的令牌數
  ARGV[4]  當前時間（毫秒，由呼叫端傳入）

  回傳 { allowed, remaining }
    allowed   1=放行 0=拒絕
    remaining 放行後桶內剩餘令牌數（無條件捨去）

  時間刻意由呼叫端傳入而非用 redis.call('TIME')：
  在 Redis 7 以前，使用 TIME 會讓腳本被判定為非確定性而無法寫入副本。
--]]

local bucketKey = KEYS[1]

local capacity   = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local requested  = tonumber(ARGV[3])
local nowMillis  = tonumber(ARGV[4])

local bucket = redis.call('HMGET', bucketKey, 'tokens', 'ts')
local tokens = tonumber(bucket[1])
local lastRefillMillis = tonumber(bucket[2])

-- 首次請求：給滿桶，讓新使用者不會一來就被限流。
if tokens == nil then
    tokens = capacity
    lastRefillMillis = nowMillis
end

-- 依經過時間補充令牌，上限為桶容量。
local elapsedSeconds = math.max(0, (nowMillis - lastRefillMillis) / 1000)
tokens = math.min(capacity, tokens + elapsedSeconds * refillRate)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HSET', bucketKey, 'tokens', tokens, 'ts', nowMillis)

-- TTL 設為「補滿整桶所需時間」的兩倍：閒置夠久的桶會被回收，
-- 且因為回收時必定已補滿，重建後的初始狀態與回收前一致，不影響限流語意。
local idleTtlSeconds = math.ceil(capacity / refillRate) * 2
redis.call('EXPIRE', bucketKey, math.max(idleTtlSeconds, 1))

return { allowed, math.floor(tokens) }
