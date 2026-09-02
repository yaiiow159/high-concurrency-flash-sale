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
 * <p>失敗不做任何事：沒有 cookie、cookie 過期、或本來就沒登入都會走到這裡，
 * 那些全是正常狀態，不是錯誤。
 */
export default defineNuxtPlugin(async () => {
  const auth = useAuthStore()
  if (auth.isAuthenticated) {
    return
  }
  await auth.refresh().catch(() => null)
})
