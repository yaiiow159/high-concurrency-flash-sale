/**
 * 產能歸因探針：同一段程式、同一組請求，只改變「機器上還有誰在跑」與「JVM 怎麼啟動」，
 * 用來回答「1,471 TPS 的上限來自設計還是環境」。
 *
 * 每一輪都先暖身（不計入）：JIT 未編譯完成時量到的是暖機曲線而不是穩態。
 *
 * CPU 取樣刻意交給獨立行程（sampler.mjs）。先前寫在同一個行程裡用 execSync，
 * 那會阻塞 Node 的事件迴圈——autocannon 就在同一條迴圈上發請求，
 * 等於每三秒把壓測工具自己暫停兩秒，量到的數字是被自己干擾過的。
 *
 * 用法：node capacity-probe.mjs <活動ID> <併發> <秒數> <情境名稱>
 */
import autocannon from 'autocannon'
import { readFileSync, writeFileSync, existsSync } from 'fs'
import { randomUUID } from 'crypto'
import { spawn } from 'child_process'

const BASE = 'http://localhost:8080'
const tokens = JSON.parse(readFileSync('tokens.json', 'utf8'))

const activityId = Number(process.argv[2])
const connections = Number(process.argv[3] ?? 500)
const duration = Number(process.argv[4] ?? 15)
const label = process.argv[5] ?? 'run'
// 售罄路徑預期回 409，不是失敗；由呼叫端指定這一輪算成功的狀態碼
const expected = (process.argv[6] ?? '2').split(',')

async function qualifyAll() {
  const results = await Promise.all(tokens.map(async (token, i) => {
    const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-Device-Id': `probe-${i}` }
    try {
      const challenge = (await (await fetch(`${BASE}/api/v1/seckill/challenge`, { headers })).json()).data
      const [, a, b] = challenge.question.match(/(\d+) \+ (\d+)/)
      const res = await (await fetch(`${BASE}/api/v1/seckill/activities/${activityId}/qualify`, {
        method: 'POST', headers,
        body: JSON.stringify({ challengeToken: challenge.challengeToken, answer: String(Number(a) + Number(b)) }),
      })).json()
      return res.code === '00000' ? res.data.token : null
    } catch { return null }
  }))
  const ok = results.filter(Boolean).length
  if (ok < tokens.length) console.log(`  ${tokens.length - ok}/${tokens.length} 個帳號沒拿到資格`)
  return results
}

function fire(qualifications, conns, secs) {
  let i = 0
  return autocannon({
    url: BASE, connections: conns, duration: secs,
    requests: [{
      method: 'POST',
      path: '/api/v1/seckill/orders',
      headers: { 'Content-Type': 'application/json' },
      setupRequest(req) {
        const index = i++ % tokens.length
        return {
          ...req,
          headers: { ...req.headers, Authorization: `Bearer ${tokens[index]}` },
          body: JSON.stringify({ activityId, quantity: 1, requestId: randomUUID(), qualificationToken: qualifications[index] }),
        }
      },
    }],
  })
}

const qualifications = await qualifyAll()

console.log(`  暖身 8s（不計入）…`)
await fire(qualifications, Math.min(50, connections), 8)

// 取樣器自己數時間、時間到寫檔——Windows 上 kill() 不會觸發 SIGTERM 處理器
const samplePath = `sample-${label}.json`
const sampler = spawn(process.execPath, ['sampler.mjs', samplePath, String(duration)], { stdio: 'ignore' })
const samplerDone = new Promise(resolve => sampler.on('exit', resolve))

console.log(`  量測 ${duration}s @ 併發 ${connections}…`)
const r = await fire(qualifications, connections, duration)
await samplerDone

let cpu = { containers: [], processes: [], containerCpuTotal: 0, nativeCoresTotal: 0 }
if (existsSync(samplePath)) cpu = JSON.parse(readFileSync(samplePath, 'utf8'))

// 非 2xx 佔多數代表這一輪根本沒打到熱路徑（token 過期、資格失敗），數字不可用
const nonSuccess = Object.entries(r.statusCodeStats)
  .filter(([code]) => !expected.some(prefix => code.startsWith(prefix)))
  .reduce((sum, [, v]) => sum + v.count, 0)
const invalid = nonSuccess > r.requests.total * 0.05

const result = {
  label, connections, duration, invalid,
  tps: Math.round(r.requests.average),
  p50: r.latency.p50, p90: r.latency.p90, p99: r.latency.p99, p999: r.latency.p99_9, max: r.latency.max,
  codes: r.statusCodeStats, total: r.requests.total,
  ...cpu,
}

console.log(`\n  [${label}] 併發 ${connections} | TPS ${result.tps} | p50 ${result.p50}ms | p90 ${result.p90}ms | p99 ${result.p99}ms`)
console.log(`  狀態碼 ${JSON.stringify(result.codes)}${invalid ? '  ⚠ 非 2xx 過多，這一輪無效' : ''}`)
if (result.containers.length) {
  console.log(`  容器 CPU 合計 ${result.containerCpuTotal}% 單核：${result.containers.map(c => `${c.name.replace('flash-sale-', '')} ${c.cpuPct}%`).join(', ')}`)
}
if (result.processes.length) {
  console.log(`  原生行程：${result.processes.map(p => `${p.name}(${p.pid}) ${p.cores}核`).join(', ')}`)
}

writeFileSync(`probe-${label}.json`, JSON.stringify(result, null, 2))
