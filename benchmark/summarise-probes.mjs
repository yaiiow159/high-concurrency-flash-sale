/**
 * 把各輪 probe-*.json 收成一張對照表。
 *
 * 每一輪只變動一個變因，因此相鄰兩列的差就是那個變因的價值。
 * 無效的輪次（非預期狀態碼過多）保留但標記出來——把失敗的嘗試藏起來，
 * 讀者就無法判斷這些數字經歷過什麼。
 */
import { readFileSync, readdirSync } from 'fs'

const NOTES = {
  A: 'C1-only JIT ＋ 全部容器 ＋ 背景 vite（原始報告條件）',
  B: 'C1-only JIT ＋ 精簡容器 ＋ 無 vite',
  C: '完整 JIT ＋ 精簡容器',
  D: '完整 JIT ＋ Tomcat 執行緒 900',
  E: 'acks=1（啟動失敗：冪等生產者要求 acks=all）',
  F: '完整 JIT ＋ 排空積壓 ＋ 500 併發',
  G: 'acks=all（生產設定）＋ 排空積壓 ＋ 200 併發',
  H: 'acks=1（啟動失敗：冪等生產者要求 acks=all）',
  I: '有庫存：走完 Redis Lua ＋ Kafka 確認',
  J: '已售罄：Caffeine 本機標記短路（前面仍有限流器的一次 Redis 往返）',
}

const rows = readdirSync('.')
  .filter(f => /^probe-[A-Z]\.json$/.test(f))
  .map(f => ({ file: f, ...JSON.parse(readFileSync(f, 'utf8')) }))
  .sort((a, b) => a.label.localeCompare(b.label))

const pad = (s, n) => String(s).padEnd(n)
const padL = (s, n) => String(s).padStart(n)

console.log('\n| 輪次 | 併發 | TPS | p50 | p90 | p99 | Java 核 | 有效 | 說明 |')
console.log('|---|---|---|---|---|---|---|---|---|')
for (const r of rows) {
  const java = r.processes?.find(p => p.name === 'java')?.cores ?? '—'
  console.log(`| ${r.label} | ${r.connections} | ${r.tps} | ${r.p50}ms | ${r.p90}ms | ${r.p99}ms | ${java} | ${r.invalid ? '✗' : '✓'} | ${NOTES[r.label] ?? ''} |`)
}

console.log('\n狀態碼：')
for (const r of rows) console.log(`  ${r.label}  ${JSON.stringify(r.codes)}`)

console.log('\n容器 CPU（單核百分比，平均）：')
for (const r of rows) {
  const c = (r.containers ?? []).map(x => `${x.name.replace('flash-sale-', '')} ${x.cpuPct}%`).join(', ')
  console.log(`  ${r.label}  合計 ${r.containerCpuTotal ?? '—'}%  ${c}`)
}
