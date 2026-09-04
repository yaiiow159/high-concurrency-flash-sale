import { login, run, table, BASE } from './bench.mjs'

const token = await login('promo1@test.com', 'password123')
const auth = { Authorization: `Bearer ${token}` }

/** 隨機翻頁，避免整場壓測都命中同一頁的快取而量出一個假的數字。 */
const randPage = () => Math.floor(Math.random() * 100)
const randProduct = () => 5 + Math.floor(Math.random() * 50000)
const randCategory = () => 16 + Math.floor(Math.random() * 180)
const KEYWORDS = ['耳機', '背包', '鍵盤', '保溫瓶', '檯燈', 'Aurora', 'Pro', '行動電源']
const randKeyword = () => KEYWORDS[Math.floor(Math.random() * KEYWORDS.length)]

const scenarios = [
  { name: '商品列表（第一頁）', path: () => '/api/v1/catalog/products?page=0&size=20' },
  { name: '商品列表（隨機翻頁）', path: () => `/api/v1/catalog/products?page=${randPage()}&size=20` },
  { name: '商品列表（依類目）', path: () => `/api/v1/catalog/products?categoryId=${randCategory()}&page=0&size=20` },
  { name: '商品列表（深分頁 offset 4 萬）', path: () => `/api/v1/catalog/products?page=${2000 + Math.floor(Math.random() * 400)}&size=20` },
  { name: '商品詳情（隨機）', path: () => `/api/v1/catalog/products/${randProduct()}` },
  { name: '類目樹（225 節點）', path: () => '/api/v1/catalog/categories' },
  { name: '搜尋（Elasticsearch）', path: () => `/api/v1/search/products?keyword=${encodeURIComponent(randKeyword())}&page=0&size=20` },
  { name: '進行中的活動', path: () => '/api/v1/activities' },
]

const rows = []
for (const s of scenarios) {
  process.stderr.write(`  跑 ${s.name} …\n`)
  rows.push(await run(s.name, {
    connections: 50,
    duration: 12,
    headers: auth,
    requests: [{ setupRequest: (req) => ({ ...req, method: 'GET', path: s.path() }) }],
  }))
}

console.log(`\n### 讀路徑（50 併發 × 12 秒，50,004 商品 / 100,007 SKU / 225 類目）\n`)
table(rows)
