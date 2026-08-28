--[[
  秒殺庫存原子扣減。

  Redis 以單執行緒執行整個腳本，因此「判重 → 查餘量 → 查限購 → 扣減 → 記錄」
  這五個動作之間不可能被其他請求插入。這是本系統防超賣的唯一強一致點，
  不需要、也不應該再額外套一層分散式鎖。

  KEYS[1]  庫存餘量鍵          seckill:{a<id>}:stock   (String)
  KEYS[2]  使用者已購量        seckill:{a<id>}:user    (Hash: userId -> qty)
  KEYS[3]  請求→訂單映射       seckill:{a<id>}:req     (Hash: requestId -> orderNo)

  ARGV[1]  userId
  ARGV[2]  本次購買數量
  ARGV[3]  每人限購上限
  ARGV[4]  requestId（端到端冪等鍵）
  ARGV[5]  本次預先產生的 orderNo
  ARGV[6]  附屬鍵的 TTL 秒數

  回傳 { code, orderNo }
     1 = 扣減成功，orderNo 為本次綁定的訂單號
     0 = 庫存不足
    -1 = 超過個人限購
    -2 = 庫存未預熱（鍵不存在）
    -3 = 重複請求，orderNo 為首次扣減時綁定的訂單號
--]]

local stockKey   = KEYS[1]
local userKey    = KEYS[2]
local requestKey = KEYS[3]

local userId       = ARGV[1]
local quantity     = tonumber(ARGV[2])
local perUserLimit = tonumber(ARGV[3])
local requestId    = ARGV[4]
local orderNo      = ARGV[5]
local ttlSeconds   = tonumber(ARGV[6])

-- 1. 冪等判重：同一個 requestId 只會扣一次，重送直接回放首次的訂單號。
local boundOrderNo = redis.call('HGET', requestKey, requestId)
if boundOrderNo then
    return { -3, boundOrderNo }
end

-- 2. 庫存必須已預熱。刻意不在此建立鍵：查不到就是預熱漏了，
--    讓它明確失敗，遠比自動建一個餘量不明的鍵安全。
local stock = redis.call('GET', stockKey)
if not stock then
    return { -2, '' }
end

if tonumber(stock) < quantity then
    return { 0, '' }
end

-- 3. 個人限購：累計已購 + 本次 不得超過上限。
local bought = tonumber(redis.call('HGET', userKey, userId) or '0')
if bought + quantity > perUserLimit then
    return { -1, '' }
end

-- 4. 扣減並落下冪等痕跡。三個寫入在同一次腳本執行內，要嘛全成功要嘛全不發生。
redis.call('DECRBY', stockKey, quantity)
redis.call('HINCRBY', userKey, userId, quantity)
redis.call('HSET', requestKey, requestId, orderNo)

-- 5. 附屬鍵首次建立時補上 TTL，避免活動結束後留下永不過期的殘骸。
if redis.call('TTL', userKey) < 0 then
    redis.call('EXPIRE', userKey, ttlSeconds)
end
if redis.call('TTL', requestKey) < 0 then
    redis.call('EXPIRE', requestKey, ttlSeconds)
end

return { 1, orderNo }
