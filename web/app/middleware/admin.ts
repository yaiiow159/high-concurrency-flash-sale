import { useAuthStore } from '~/stores/auth'

/**
 * `/admin` 的路由守衛。
 *
 * # 這不是安全邊界
 *
 * 它只做一件事：**沒有 admin 權限的人不要看到後台的殼**。
 * 防的是「誤觸」與「畫面上出現一堆按下去會失敗的按鈕」，不是防攻擊者。
 *
 * 真正的授權在後端（`SecurityConfig`）：
 *
 * ```java
 * .requestMatchers("/api/v1/admin/**").hasAuthority(SCOPE_ADMIN)
 * ```
 *
 * 這個守衛可以被繞過——改 JS、直接打 API 都行——**而那不重要**，
 * 因為繞過之後打到的每一支端點都還是會回 403。
 *
 * 推論：後台**不新增任何「只有前端知道」的規則**。
 * 「只有 admin 能看到全部訂單」不能靠前端不顯示入口來達成，
 * 必須是那支端點本身就要 admin scope（ADR-0015 決策 2）。
 *
 * # 為什麼要等
 *
 * access token 只存在記憶體，開機時靠 refresh cookie 靜默續期，
 * 而那件事發生在 hydration **之後**（見 `plugins/auth.client.ts`）。
 * 守衛若在那之前就判定「沒登入」，重新整理 `/admin` 會把
 * 已登入的維運人員踢回首頁——而他再按一次上一頁又進得來，
 * 那是最容易被當成偶發問題的那種 bug。
 */
export default defineNuxtRouteMiddleware(async (to) => {
  // 伺服器端一律放行：後台頁面本來就 ssr: false，
  // 真正的判斷在客戶端做。在這裡擋只會產生一次沒有意義的重導
  if (import.meta.server) {
    return
  }

  const auth = useAuthStore()

  // 還沒續期過就等它一次。失敗代表真的沒登入，不是錯誤
  if (!auth.isAuthenticated) {
    await auth.refresh().catch(() => null)
  }

  if (!auth.isAdmin) {
    return navigateTo({ path: '/', query: { denied: to.path } })
  }
})
