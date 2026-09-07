export default defineNuxtConfig({
  compatibilityDate: '2025-01-01',
  devtools: { enabled: false },

  // 3000 是 docker-compose 裡 Grafana 的埠，會衝突。
  // 用 5173 避開，順便與 Vite 的慣例一致。
  devServer: { port: 5173 },

  // 明確指定目錄結構：前端原始碼在 app/，BFF 伺服器路由在 server/。
  // 不倚賴 Nuxt 版本的預設值——那在 3.x 與 4.x 之間有差異，
  // 升級時會變成一個難以一眼看出原因的解析錯誤。
  srcDir: 'app/',
  serverDir: 'server/',
  modules: ['@pinia/nuxt', '@nuxtjs/tailwindcss'],
  css: ['~/assets/css/main.css'],

  runtimeConfig: {
    // 僅伺服器端可見：BFF 用它呼叫後端，瀏覽器永遠拿不到
    apiBase: process.env.NUXT_API_BASE || 'http://localhost:8080',
    public: {
      // 對外網址。沒設就從請求標頭推——寫死的話同一份產物部署到
      // 測試環境會產生指向正式站的 sitemap 與 canonical
      siteUrl: process.env.NUXT_PUBLIC_SITE_URL || '',
      // 後台「查看追蹤」跳轉用。只是一個連結的目的地，不含任何憑證
      grafanaUrl: process.env.NUXT_PUBLIC_GRAFANA_URL || 'http://localhost:3000',
    },
  },

  /**
   * 渲染策略逐頁指定，而非全站一刀切。 秒殺頁是削峰漏斗的第 0 層：靜態部分必須由 CDN 完全承接， 100 萬次瀏覽不該有一次打到 origin。庫存數字則走獨立的輕量請求， 與頁面本體解耦——它變動極快，快取它只會讓使用者看到過期數字。
   */
  routeRules: {
    // `isr` 與 `cache` 兩個都要給：`isr` 是平台層指示（Vercel/Netlify 才讀），
    // 自架的 node-server 只認 `cache`。只寫 isr 的話實測完全沒有快取。
    '/': { isr: 60, cache: { maxAge: 60 } },
    // sitemap 每次都要查資料庫，而爬蟲會反覆打。一小時的快取足夠新鮮
    '/sitemap.xml': { cache: { maxAge: 3600 } },
    '/sitemap/**': { cache: { maxAge: 3600 } },
    '/robots.txt': { cache: { maxAge: 86400 } },
    '/seckill/**': { isr: 300, cache: { maxAge: 300 } },

    // 商品頁同樣可快取：回應不含庫存也不含身分（庫存另外請求）。
    // 已驗證匿名與已登入的 SSR 輸出逐位元組相同，因此共用快取不會外洩個資
    '/products': { isr: 300, cache: { maxAge: 300 } },
    // 排行榜與首頁一樣不含身分；銷量每五分鐘變一次名次沒有人會發現
    '/rankings': { isr: 300, cache: { maxAge: 300 } },
    '/products/**': { isr: 300, cache: { maxAge: 300 } },

    // 訂單頁絕不快取——那是安全邊界而非效能取捨：被 CDN 快取等於把某個人的訂單發給下一個訪客
    '/orders': { isr: false },
    '/orders/**': { isr: false },

    /** 通知同理：它帶著訂單號與金額，而且是寫給特定一個人看的。 */
    '/notifications': { isr: false },
    /** 帳戶總覽與瀏覽紀錄同理：整頁都是某一個人的資料。 */
    '/account': { isr: false },
    '/history': { isr: false },

    /** 搜尋結果不快取：結果隨關鍵字而異，快取等於為每一種組合各存一份， 命中率趨近於零，卻要付出全部的儲存與失效成本。 */
    '/search': { isr: false },

    /** 退貨單同理：它帶著訂單號、商品與金額，是個人資料。 */
    '/returns': { isr: false },
    '/returns/**': { isr: false },

    /** 地址簿同理：個資進了快取的 HTML 就等於發給下一個訪客。 */
    '/addresses': { isr: false },

    /** 模擬付款頁帶著付款單號與金額，不快取。 */
    '/pay/**': { isr: false },

    /** 購物車與結帳頁都是每個人專屬的內容，一律不快取。 */
    '/cart': { isr: false },
    '/checkout': { isr: false },

    /** 代理到後端，避開 CORS。 這也更貼近正式環境：前後端在同一個網域後面， 而不是靠 CORS 標頭放行跨域——那在生產環境是額外的攻擊面。 */
    '/api/v1/**': {
      proxy: { to: `${process.env.NUXT_API_BASE || 'http://localhost:8080'}/api/v1/**` },
    },
  },

  typescript: { strict: true, typeCheck: false },
})
