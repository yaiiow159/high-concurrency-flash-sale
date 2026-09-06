import { useAuthStore } from '~/stores/auth'

/** 開機時嘗試一次靜默續期。 */
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
