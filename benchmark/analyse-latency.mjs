/**
 * 比對「應用內部量到的耗時」與「用戶端量到的延遲」。
 *
 * seckill_attempt_duration 是 attempt() 進出之間的時間，涵蓋 Redis Lua 與
 * 等待 Kafka 確認；用戶端的延遲還多了連線排隊、HTTP 解析與回應寫出。
 * 兩者的差就是「請求排在門口的時間」，這決定了瓶頸在應用內還是應用外。
 *
 * Prometheus 的直方圖是累積型（le 是上界、值為累計次數），
 * 因此要先做前後相減，才是這一輪壓測期間的分佈。
 */
import { readFileSync } from 'fs'

function parseHistogram(text, metric) {
  const buckets = new Map()
  let count = 0
  let sum = 0
  for (const line of text.split('\n')) {
    if (line.startsWith('#') || !line.startsWith(metric)) continue
    const [key, rawValue] = line.split(/\s+/)
    const value = Number(rawValue)
    if (key.startsWith(`${metric}_bucket`)) {
      const le = key.match(/le="([^"]+)"/)?.[1]
      if (le) buckets.set(le, (buckets.get(le) ?? 0) + value)
    } else if (key.startsWith(`${metric}_count`)) count += value
    else if (key.startsWith(`${metric}_sum`)) sum += value
  }
  return { buckets, count, sum }
}

/** 累積直方圖相減後求分位數：找出第一個累計次數越過目標的桶上界 */
function quantile(buckets, total, q) {
  const target = total * q
  const sorted = [...buckets.entries()]
    .map(([le, v]) => [le === '+Inf' ? Infinity : Number(le), v])
    .sort((a, b) => a[0] - b[0])
  for (const [le, v] of sorted) if (v >= target) return le
  return Infinity
}

const METRIC = 'seckill_attempt_duration_seconds'
const before = parseHistogram(readFileSync('metrics-before.txt', 'utf8'), METRIC)
const after = parseHistogram(readFileSync('metrics-after.txt', 'utf8'), METRIC)

const delta = new Map()
for (const [le, v] of after.buckets) delta.set(le, v - (before.buckets.get(le) ?? 0))
const count = after.count - before.count
const sum = after.sum - before.sum

if (count <= 0) {
  console.log('\n  沒有取得有效的應用內部耗時樣本')
  process.exit(0)
}

const label = process.argv[2] ?? 'F'
const probe = JSON.parse(readFileSync(`probe-${label}.json`, 'utf8'))
const meanMs = (sum / count) * 1000
const p50 = quantile(delta, count, 0.5) * 1000
const p90 = quantile(delta, count, 0.9) * 1000
const p99 = quantile(delta, count, 0.99) * 1000

console.log('\n=== 延遲歸因 ===')
console.log(`  樣本 ${count} 筆`)
console.log(`  應用內部 attempt()   平均 ${meanMs.toFixed(1)}ms | p50 ${p50.toFixed(0)}ms | p90 ${p90.toFixed(0)}ms | p99 ${p99.toFixed(0)}ms`)
console.log(`  用戶端觀測           p50 ${probe.p50}ms | p90 ${probe.p90}ms | p99 ${probe.p99}ms`)
console.log(`  差額（應用外排隊）   p50 ${(probe.p50 - p50).toFixed(0)}ms | p90 ${(probe.p90 - p90).toFixed(0)}ms`)
const cpuPerReq = (probe.processes?.find(p => p.name === 'java')?.cores ?? 0) / probe.tps * 1000
console.log(`  每請求 CPU           ${cpuPerReq.toFixed(2)}ms（應用內部耗時的 ${(cpuPerReq / meanMs * 100).toFixed(0)}%）`)
console.log(`  TPS ${probe.tps} | Java ${probe.processes?.find(p => p.name === 'java')?.cores ?? '?'} 核 / 20`)
