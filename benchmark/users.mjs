/** 建一批壓測用帳號並取得 token。單一使用者會被單使用者維度的限流擋住。 */
const BASE = 'http://localhost:8080'
const N = 60
const tokens = []
for (let i = 0; i < N; i++) {
  const email = `loadtest${i}@perf.test`
  await fetch(`${BASE}/api/v1/auth/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123', displayName: `壓測 ${i}` }),
  }).catch(() => {})
  const r = await fetch(`${BASE}/api/v1/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password: 'password123' }),
  }).then(r => r.json())
  if (r.data?.accessToken) tokens.push(r.data.accessToken)
}
await import('fs').then(fs => fs.writeFileSync('tokens.json', JSON.stringify(tokens)))
console.log(`  取得 ${tokens.length} 個 token`)
