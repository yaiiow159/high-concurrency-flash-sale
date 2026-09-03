import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import autoImport from 'unplugin-auto-import/vite'
import { defineConfig } from 'vitest/config'

/**
 * 前端測試。
 *
 * <b>刻意不用 @nuxt/test-utils 的完整 Nuxt 環境。</b>
 * 那個要真的啟動一次 Nuxt，單檔就要數十秒；而這裡要測的東西——
 * 純函式、Pinia store、以及元件在不同資料下渲染成什麼——
 * 都不需要 Nuxt 的執行期。需要用到自動匯入（ref/computed）的地方，
 * 在測試檔裡明確 import 即可，那反而更清楚。
 */
export default defineConfig({
  plugins: [
    vue(),
    // 元件是寫給 Nuxt 的，ref/computed/watch 都靠自動匯入。
    // 這裡補上同一組 Vue API，元件原始碼才不必為了測試而改寫——
    // 為了好測而改動產品程式碼，測的就不是實際跑的那份了。
    autoImport({ imports: ['vue'], dts: false }),
  ],
  test: {
    environment: 'happy-dom',
    include: ['tests/**/*.spec.ts'],
    globals: true,
  },
  resolve: {
    alias: {
      '~': fileURLToPath(new URL('./app', import.meta.url)),
      '@': fileURLToPath(new URL('./app', import.meta.url)),
    },
  },
})
