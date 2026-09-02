import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'

/**
 * 登入後把本地購物車併入伺服器端。
 *
 * <p>掛在 `auth.isAuthenticated` 的變化上，而不是寫進登入表單裡：
 * 登入的入口不只一個（登入面板、開機靜默續期、之後可能有的 OAuth），
 * 每個入口各接一次合併，遲早會有一個漏掉——
 * 而漏掉的症狀是「登入後購物車空了」，使用者只會以為東西被吃掉了。
 *
 * <p>檔名的 `.client` 不可省。合併會寫入伺服器端狀態，
 * 在 SSR 期間執行等於讓一次頁面生成改動使用者的資料；
 * 而且 localStorage 在伺服器上根本不存在。
 *
 * <p>`auth.client.ts` 依字母序先執行，因此這裡看到的登入狀態
 * 已經是靜默續期之後的結果。
 */
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
