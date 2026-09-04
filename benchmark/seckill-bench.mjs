import autocannon from 'autocannon'
import { readFileSync } from 'fs'
import { randomUUID } from 'crypto'

const tokens = JSON.parse(readFileSync('tokens.json', 'utf8'))
const BASE = 'http://localhost:8080'

async function run(name, activityId, connections, duration) {
  let i = 0
  const r = await autocannon({
    url: BASE, connections, duration,
    requests: [{
      method: 'POST',
      path: '/api/v1/seckill/orders',
      headers: { 'Content-Type': 'application/json' },
      setupRequest(req) {
        const token = tokens[i++ % tokens.length]
        return {
          ...req,
          headers: { ...req.headers, Authorization: `Bearer ${token}` },
          // requestId 每次都不同——重複的話會命中冪等映射，
          // 量到的就是「重送保護」而不是實際扣減
          body: JSON.stringify({ activityId, quantity: 1, requestId: randomUUID() }),
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
