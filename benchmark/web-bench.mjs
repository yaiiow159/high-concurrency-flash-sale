import autocannon from 'autocannon'

const pages = [
  { name: '/products（列表，可快取）', path: '/products' },
  { name: '/products/25000（詳情）', path: '/products/25000' },
  { name: '/（首頁）', path: '/' },
  { name: '/cart（不可快取）', path: '/cart' },
]
const rows = []
for (const p of pages) {
  const r = await autocannon({ url: 'http://localhost:3100', connections: 50, duration: 12,
    requests: [{ method: 'GET', path: p.path }] })
  rows.push({ name: p.name, rps: r.requests.average, p50: r.latency.p50, p90: r.latency.p90,
    p99: r.latency.p99, max: r.latency.max, codes: JSON.stringify(r.statusCodeStats) })
}
const w = (s) => [...s].reduce((n, c) => n + (/[　-鿿＀-￯]/.test(c) ? 2 : 1), 0)
const pad = (s, n) => s + ' '.repeat(Math.max(0, n - w(s)))
const head = ['頁面', 'QPS', 'p50', 'p90', 'p99', 'max']
const body = rows.map(r => [r.name, r.rps.toFixed(0), `${r.p50}ms`, `${r.p90}ms`, `${r.p99}ms`, `${r.max}ms`])
const ws = head.map((h, i) => Math.max(w(h), ...body.map(b => w(b[i]))))
console.log('| ' + head.map((h,i)=>pad(h,ws[i])).join(' | ') + ' |')
console.log('|' + ws.map(x=>'-'.repeat(x+2)).join('|') + '|')
body.forEach(b => console.log('| ' + b.map((c,i)=>pad(c,ws[i])).join(' | ') + ' |'))
rows.forEach(r => { if (!r.codes.includes('"200"') || Object.keys(JSON.parse(r.codes)).length > 1) console.log(`  ${r.name} 狀態碼: ${r.codes}`) })
