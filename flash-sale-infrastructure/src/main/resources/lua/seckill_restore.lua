--[[
  秒殺庫存補償退回（Saga 補償動作）。

  冪等性由「請求→訂單」映射保證：退庫時把映射刪除，
  重複呼叫因為找不到映射而直接返回，不會把庫存退兩次。
  補償排程與 DLQ 消費端可能同時對同一筆訂單發起退庫，這道保險是必要的。

  KEYS[1]  庫存餘量鍵     seckill:{a<id>}:stock
  KEYS[2]  使用者已購量   seckill:{a<id>}:user
  KEYS[3]  請求→訂單映射  seckill:{a<id>}:req

  ARGV[1]  userId
  ARGV[2]  退回數量
  ARGV[3]  requestId

  回傳
     1 = 確實退回了庫存
     0 = 無需退回（未曾扣減、已退過，或活動庫存鍵已過期）
--]]

local stockKey   = KEYS[1]
local userKey    = KEYS[2]
local requestKey = KEYS[3]

local userId    = ARGV[1]
local quantity  = tonumber(ARGV[2])
local requestId = ARGV[3]

-- 1. 沒有扣減痕跡就沒有東西可退——這是冪等的關鍵判斷。
if not redis.call('HGET', requestKey, requestId) then
    return 0
end

-- 2. 庫存鍵已過期代表活動早已結束，此時 INCRBY 會憑空造出一個沒有 TTL 的鍵，
--    反而製造出「幽靈庫存」。清掉痕跡即可，不做退回。
if redis.call('EXISTS', stockKey) == 0 then
    redis.call('HDEL', requestKey, requestId)
    return 0
end

redis.call('HDEL', requestKey, requestId)
redis.call('INCRBY', stockKey, quantity)

local remaining = redis.call('HINCRBY', userKey, userId, -quantity)
if remaining <= 0 then
    redis.call('HDEL', userKey, userId)
end

return 1
