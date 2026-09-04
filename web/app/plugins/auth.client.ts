import { useAuthStore } from '~/stores/auth'

/**
 * 開機時嘗試一次靜默續期。
 *
 * <p>access token 只存在記憶體裡（刻意的：進了 localStorage 就會被 XSS 讀走），
 * 因此每次整頁載入都會消失。少了這一步，使用者明明有有效的 refresh cookie，
 * 畫面卻顯示成未登入——直到某個請求剛好撞到 401 才會恢復。
 * 表現出來就是「重新整理後被登出，但按一下又好了」這種最難查的問題。
 *
 * <p><b>刻意只在客戶端執行</b>（`.client.ts`）。兩個理由：
 * <ul>
 *   <li>商品頁與首頁是 ISR 快取的，登入狀態若進了 SSR 的 HTML，
 *       就會連同快取一起發給下一個訪客</li>
 *   <li>續期會輪替 refresh token；在 SSR 期間做這件事，
 *       等於讓一次頁面預算生成消耗掉使用者的令牌</li>
 * </ul>
 *
 * <h2>必須等 hydration 結束才能動 store</h2>
 *
 * <p><b>這個外掛先前直接 `await auth.refresh()`，那是一個會弄壞整個前端的錯。</b>
 *
 * <p>Nuxt 的外掛跑在 hydration <b>之前</b>。續期一旦在那時完成，
 * 客戶端第一次渲染看到的就是「已登入」，而伺服器送來的 HTML 是「未登入」——
 * 兩棵樹對不起來。Vue 接手失敗後丟出
 * {@code insertBefore ... is not a child of this node}，
 * 接著整棵元件樹的事件處理器就死了。
 *
 * <p>症狀不是「畫面閃一下」而是**頁面沒有反應**：`/cart` 畫得出來但按鈕點不動，
 * `/checkout` 直接渲染成空白。而且它只在「有 refresh cookie」時發生，
 * 所以本機第一次開沒事、登入過一次之後才開始壞——最容易被誤判成偶發問題。
 *
 * <p>因此改用 {@code onNuxtReady}：它在 hydration 完成後才觸發，
 * 此時再改 store 就是一次正常的響應式更新，Vue 會自己重繪頁首。
 * 代價是登入狀態會晚幾十毫秒出現，那是這個設計無法避免的——
 * 伺服器端本來就不該知道你是誰（見上面 ISR 那條）。
 */
export default defineNuxtPlugin(() => {
  const auth = useAuthStore()

  onNuxtReady(async () => {
    if (auth.isAuthenticated) {
      return
    }
    // 失敗不做任何事：沒有 cookie、cookie 過期、或本來就沒登入都會走到這裡，
    // 那些全是正常狀態，不是錯誤
    await auth.refresh().catch(() => null)
  })
})
