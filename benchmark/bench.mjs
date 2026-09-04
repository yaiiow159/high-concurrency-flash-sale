/**
 * 讀路徑與熱路徑的壓測。
 *
 * 用 autocannon 在**本機原生**跑，不進容器——容器多一跳網路，
 * 而這次要量的正是延遲本身，多出來的 0.x 毫秒會混進 p99。
 */
import autocannon from 'autocannon'

const BASE = 'http://localhost:8080'

async function login(email, password) {
  const res = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  const body = await res.json()
  if (!body.data?.accessToken) throw new Error(`login failed: ${JSON.stringify(body)}`)
  return body.data.accessToken
}

/** 跑一個情境並取出要報告的數字。 */
async function run(name, opts) {
  const result = await autocannon({
    url: BASE,
    connections: opts.connections ?? 50,
    duration: opts.duration ?? 15,
    ...opts,
  })
  // 伺服器回的非 2xx 與**用戶端 socket 錯誤**要分開報。
  // 把兩者相加會把壓測機自己的連線問題記到受測系統頭上——
  // 實測 50 併發下有約 0.9% 的 socket error，而伺服器端全部是 200
  return {
    name,
    connections: result.connections,
    rps: result.requests.average,
    p50: result.latency.p50,
    p90: result.latency.p90,
    p99: result.latency.p99,
    p999: result.latency.p99_9,
    max: result.latency.max,
    total: result.requests.total,
    non2xx: result.non2xx,
    clientErrors: (result.errors ?? 0) + (result.timeouts ?? 0),
    throughputMB: (result.throughput.average / 1024 / 1024).toFixed(2),
  }
}

function table(rows) {
  const head = ['情境', '併發', 'QPS', 'p50', 'p90', 'p99', 'p99.9', 'max', '總請求', '非 2xx', 'socket err']
  const body = rows.map(r => [
    r.name, String(r.connections), r.rps.toFixed(0),
    `${r.p50}ms`, `${r.p90}ms`, `${r.p99}ms`, `${r.p999}ms`, `${r.max}ms`,
    String(r.total), String(r.non2xx), String(r.clientErrors),
  ])
  const widths = head.map((h, i) =>
    Math.max(strWidth(h), ...body.map(r => strWidth(r[i]))))
  const line = (cells) => '| ' + cells.map((c, i) => pad(c, widths[i])).join(' | ') + ' |'
  console.log(line(head))
  console.log('|' + widths.map(w => '-'.repeat(w + 2)).join('|') + '|')
  body.forEach(r => console.log(line(r)))
}

// 中文字在終端機佔兩格，不補償的話表格會歪
const strWidth = (s) => [...s].reduce((n, ch) => n + (/[　-鿿＀-￯]/.test(ch) ? 2 : 1), 0)
const pad = (s, w) => s + ' '.repeat(Math.max(0, w - strWidth(s)))

export { login, run, table, BASE }
