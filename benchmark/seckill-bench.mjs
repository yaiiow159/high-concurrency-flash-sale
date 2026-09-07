import autocannon from 'autocannon'
import { readFileSync } from 'fs'
import { randomUUID } from 'crypto'

const tokens = JSON.parse(readFileSync('tokens.json', 'utf8'))
const BASE = 'http://localhost:8080'

/**
 * 每個帳號先領搶購資格（ADR-0028）。這一步是冷路徑，不算進壓測——
 * 真實使用者也是開賣前就領好的。答題用正則從「a + b = ?」算出來。
 */
async function qualifyAll(activityId) {
  const results = await Promise.all(tokens.map(async (token, i) => {
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json',
      'X-Device-Id': `bench-device-${i}` }
    const challenge = (await (await fetch(`${BASE}/api/v1/seckill/challenge`, { headers })).json()).data
    const [, a, b] = challenge.question.match(/(\d+) \+ (\d+)/)
    const res = await (await fetch(`${BASE}/api/v1/seckill/activities/${activityId}/qualify`, {
      method: 'POST', headers,
      body: JSON.stringify({ challengeToken: challenge.challengeToken, answer: String(Number(a) + Number(b)) }),
    })).json()
    return res.code === '00000' ? res.data.token : null
  }))
  const missing = results.filter(r => r === null).length
  if (missing > 0) console.log(`  ${missing}/${tokens.length} 個帳號沒拿到資格（風控或活動時間），它們的請求會被 403 擋下`)
  return results
}

async function run(name, activityId, connections, duration) {
  const qualifications = await qualifyAll(activityId)
  let i = 0
  const r = await autocannon({
    url: BASE, connections, duration,
    requests: [{
      method: 'POST',
      path: '/api/v1/seckill/orders',
      headers: { 'Content-Type': 'application/json' },
      setupRequest(req) {
        const index = i++ % tokens.length
        return {
          ...req,
          headers: { ...req.headers, Authorization: `Bearer ${tokens[index]}` },
          // requestId 每次都不同——重複的話會命中冪等映射，
          // 量到的就是「重送保護」而不是實際扣減
          body: JSON.stringify({ activityId, quantity: 1, requestId: randomUUID(),
            qualificationToken: qualifications[index] }),
        }
      },
    }],
  })
  return { name, connections, rps: r.requests.average, p50: r.latency.p50, p90: r.latency.p90,
    p99: r.latency.p99, p999: r.latency.p99_9, max: r.latency.max,
    codes: r.statusCodeStats, total: r.requests.total }
}

const args = process.argv.slice(2)
const activityId = Number(args[0]); const conns = Number(args[1] ?? 50); const dur = Number(args[2] ?? 12)
const res = await run('run', activityId, conns, dur)
console.log(`  併發 ${res.connections} | QPS ${res.rps.toFixed(0)} | p50 ${res.p50}ms | p90 ${res.p90}ms | p99 ${res.p99}ms | p99.9 ${res.p999}ms | max ${res.max}ms`)
console.log(`  狀態碼 ${JSON.stringify(res.codes)} | 總請求 ${res.total}`)
