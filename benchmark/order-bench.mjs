/**
 * 一般下單通道的壓測。
 *
 * 這條路徑與秒殺**剛好相反**：單一資料庫交易、MySQL 條件式 UPDATE 扣庫存、
 * 失敗全回滾。它沒有削峰，所以資料庫就是它的天花板。
 *
 * ADR-0008 的前提是「數萬個 SKU 各自獨立、衝突率極低，DB 完全夠用」。
 * 這支腳本就是去驗證那句話——並且找出它什麼時候不成立。
 *
 *   node order-bench.mjs
 */
import autocannon from 'autocannon'
import { randomUUID } from 'crypto'

const BASE = 'http://localhost:8080'

/** 壓測帳號數。與 users.mjs 一致——單一帳號會被單使用者維度的限流擋住。 */
const ACCOUNTS = 60

/**
 * 每次跑都重新登入，不吃 tokens.json。
 *
 * access token 只有 15 分鐘，而重跑壓測常常隔了幾小時。
 * 讀舊檔的話會拿到一批過期令牌，症狀是「0 個帳號備妥地址」——
 * 看起來像帳號沒建好，其實只是令牌過期。
 */
async function login(email) {
  const r = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123' }),
  }).then(r => r.json()).catch(() => ({}))
  return r.data?.accessToken ?? null
}

/** 種入的 SKU 區間，見 seed-catalog.sql。 */
const SKU_MIN = 2001
const SKU_MAX = 102014

/**
 * 集中情境用的單一 SKU。
 *
 * 庫存要夠大——賣完之後量到的就是「拒絕」而不是「扣減」，
 * 那是另一件事的成本。
 */
const HOT_SKU = 2003

/** 每個帳號一個收貨地址。下單一定要地址，缺了會在驗證階段就被擋掉。 */
async function ensureAddress(token) {
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
  const existing = await fetch(`${BASE}/api/v1/addresses`, { headers }).then(r => r.json())
  if (existing.data?.length) {
    return existing.data[0].addressId
  }
  const created = await fetch(`${BASE}/api/v1/addresses`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      recipientName: '壓測', phone: '0912345678', postalCode: '110',
      region: '台北市', district: '信義區', streetAddress: '市府路1號',
      defaultAddress: true,
    }),
  }).then(r => r.json())
  return created.data?.addressId
}

const randomSku = () => SKU_MIN + Math.floor(Math.random() * (SKU_MAX - SKU_MIN + 1))

async function run(name, { connections, duration, skuOf }) {
  let i = 0
  const r = await autocannon({
    url: BASE,
    connections,
    duration,
    requests: [{
      method: 'POST',
      path: '/api/v1/orders',
      headers: { 'Content-Type': 'application/json' },
      setupRequest(req) {
        const seat = i++ % accounts.length
        const { token, addressId } = accounts[seat]
        return {
          ...req,
          headers: { ...req.headers, Authorization: `Bearer ${token}` },
          // requestId 每次都要不同。重複的話會命中冪等而直接回既有訂單，
          // 量到的是「重送保護」而不是真的下單
          body: JSON.stringify({
            items: [{ skuId: skuOf(), quantity: 1 }],
            requestId: randomUUID(),
            addressId,
            shippingMethod: 'HOME_DELIVERY',
          }),
        }
      },
    }],
  })
  return {
    name,
    connections,
    rps: r.requests.average,
    p50: r.latency.p50,
    p90: r.latency.p90,
    p99: r.latency.p99,
    max: r.latency.max,
    total: r.requests.total,
    codes: r.statusCodeStats ?? {},
    non2xx: r.non2xx,
  }
}

const accounts = []
for (let i = 0; i < ACCOUNTS; i++) {
  const token = await login(`loadtest${i}@perf.test`)
  if (!token) continue
  const addressId = await ensureAddress(token)
  if (addressId) accounts.push({ token, addressId })
}
if (accounts.length === 0) {
  throw new Error('沒有可用的壓測帳號——先跑 node users.mjs 建立它們')
}
process.stderr.write(`  ${accounts.length} 個帳號備妥地址\n`)

// 只跑其中一種情境：node order-bench.mjs 集中
// 重驗某個修正時不必等完整八輪（一輪 12 秒，全跑約兩分半）
const only = process.argv[2]
const rows = []

if (only !== '集中') {
  for (const connections of [20, 50, 100, 200]) {
    process.stderr.write(`  分散 ${connections} 併發 …\n`)
    rows.push(await run(`分散（10 萬 SKU 隨機）`, { connections, duration: 12, skuOf: randomSku }))
  }
}
if (only !== '分散') {
  for (const connections of [20, 50, 100, 200]) {
    process.stderr.write(`  集中 ${connections} 併發 …\n`)
    rows.push(await run(`集中（全部搶同一個 SKU）`, { connections, duration: 12, skuOf: () => HOT_SKU }))
  }
}

const w = (s) => [...s].reduce((n, c) => n + (/[　-鿿＀-￯]/.test(c) ? 2 : 1), 0)
const pad = (s, n) => s + ' '.repeat(Math.max(0, n - w(s)))
const head = ['情境', '併發', 'TPS', 'p50', 'p90', 'p99', 'max', '總請求', '非 2xx']
const body = rows.map(r => [
  r.name, String(r.connections), r.rps.toFixed(0),
  `${r.p50}ms`, `${r.p90}ms`, `${r.p99}ms`, `${r.max}ms`,
  String(r.total), String(r.non2xx),
])
const ws = head.map((h, i) => Math.max(w(h), ...body.map(b => w(b[i]))))
console.log('\n### 一般下單（同步交易通道）\n')
console.log('| ' + head.map((h, i) => pad(h, ws[i])).join(' | ') + ' |')
console.log('|' + ws.map(x => '-'.repeat(x + 2)).join('|') + '|')
body.forEach(b => console.log('| ' + b.map((c, i) => pad(c, ws[i])).join(' | ') + ' |'))
console.log()
rows.forEach(r => console.log(`  ${r.name} ${r.connections}：狀態碼 ${JSON.stringify(r.codes)}`))
