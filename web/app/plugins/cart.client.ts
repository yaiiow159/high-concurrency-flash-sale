import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'

/** 登入後把本地購物車併入伺服器端。 */
export default defineNuxtPlugin(() => {
  const auth = useAuthStore()
  const cart = useCartStore()

  watch(() => auth.isAuthenticated, async (loggedIn, wasLoggedIn) => {
    if (!loggedIn || wasLoggedIn) {
      return
    }
    // 合併失敗不該讓使用者卡住——本地購物車還在，下次登入會再試一次。
    // 真正不能接受的是「合併失敗且本地被清掉」，而 mergeAfterLogin
    // 只在成功後才清本地，正是為了這個。
    await cart.mergeAfterLogin().catch(() => cart.load().catch(() => undefined))
  }, { immediate: true })
})
