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
  },

  /**
   * 渲染策略逐頁指定，而非全站一刀切。
   *
   * 秒殺頁是削峰漏斗的第 0 層：靜態部分必須由 CDN 完全承接，
   * 100 萬次瀏覽不該有一次打到 origin。庫存數字則走獨立的輕量請求，
   * 與頁面本體解耦——它變動極快，快取它只會讓使用者看到過期數字。
   */
  routeRules: {
    // 首頁與秒殺頁：ISR，CDN 可長時間快取，內容變動由再驗證處理
    '/': { isr: 60 },
    '/seckill/**': { isr: 300 },

    // 商品頁同樣可快取：回應不含庫存也不含身分（庫存另外請求）
    '/products': { isr: 300 },
    '/products/**': { isr: 300 },

    /**
     * 訂單頁**絕不快取**。
     *
     * 訂單是每個使用者專屬的資料，被 CDN 快取等於把某個人的訂單
     * 發給下一個訪客。這一條不是效能取捨，是安全邊界。
     */
    '/orders/**': { isr: false },

    /** 地址簿同理：個資進了快取的 HTML 就等於發給下一個訪客。 */
    '/addresses': { isr: false },

    /** 購物車與結帳頁都是每個人專屬的內容，一律不快取。 */
    '/cart': { isr: false },
    '/checkout': { isr: false },

    /**
     * 代理到後端，避開 CORS。
     *
     * 這也更貼近正式環境：前後端在同一個網域後面，
     * 而不是靠 CORS 標頭放行跨域——那在生產環境是額外的攻擊面。
     */
    '/api/v1/**': {
      proxy: { to: `${process.env.NUXT_API_BASE || 'http://localhost:8080'}/api/v1/**` },
    },
  },

  typescript: { strict: true, typeCheck: false },
})
