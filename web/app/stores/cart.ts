import { defineStore } from 'pinia'
import { useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { CartView } from '~/types/api'

const LOCAL_KEY = 'flash-sale.cart'
const MAX_ITEMS = 50
const MAX_QUANTITY = 999

interface LocalItem {
  skuId: number
  quantity: number
}

/**
 * 購物車。
 *
 * 未登入時放 localStorage，登入後併進伺服器端。這讓「先逛再登入」成為可能，
 * 而不是逼使用者一進站就登入。
 *
 * **本地購物車只存 skuId 與數量，不存價格。**
 * 價格每次都由伺服器回傳——存在瀏覽器裡的價格既會過期，也是使用者改得動的。
 * 這與伺服器端購物車不存價格是同一個理由，也與訂單必須存快照剛好相反。
 */
export const useCartStore = defineStore('cart', () => {
  const auth = useAuthStore()
  const { request } = useApi()

  /** 已登入時的購物車內容，由伺服器提供。 */
  const remote = ref<CartView | null>(null)
  /** 未登入時的本地購物車。 */
  const local = ref<LocalItem[]>([])
  const loading = ref(false)

  /**
   * 購物車圖示上的數字。
   *
   * 未登入時只數本地品項——本地沒有價格，也算不出金額，
   * 但「有幾件」這個資訊不需要伺服器就給得出來。
   */
  const itemCount = computed(() =>
    auth.isAuthenticated
      ? (remote.value?.totalQuantity ?? 0)
      : local.value.reduce((sum, item) => sum + item.quantity, 0),
  )

  function readLocal(): LocalItem[] {
    if (import.meta.server) {
      return []
    }
    try {
      const raw = localStorage.getItem(LOCAL_KEY)
      if (!raw) {
        return []
      }
      // localStorage 是使用者改得動的，內容一律不可信：
      // 逐筆驗證形狀與範圍，壞掉的直接丟棄而不是讓整個購物車炸掉
      const parsed: unknown = JSON.parse(raw)
      if (!Array.isArray(parsed)) {
        return []
      }
      return parsed
        .filter((item): item is LocalItem =>
          typeof item === 'object' && item !== null
          && Number.isInteger((item as LocalItem).skuId)
          && Number.isInteger((item as LocalItem).quantity)
          && (item as LocalItem).quantity > 0
          && (item as LocalItem).quantity <= MAX_QUANTITY)
        .slice(0, MAX_ITEMS)
    } catch {
      // JSON 壞掉、localStorage 被停用（無痕視窗）都會走到這裡，
      // 兩者都是正常狀態而非錯誤
      return []
    }
  }

  function writeLocal(items: LocalItem[]): void {
    if (import.meta.server) {
      return
    }
    try {
      localStorage.setItem(LOCAL_KEY, JSON.stringify(items))
    } catch {
      // 配額滿或被停用。購物車存不進去不該讓頁面壞掉——
      // 使用者仍然可以下單，只是換頁後會忘記
    }
  }

  function clearLocal(): void {
    local.value = []
    if (!import.meta.server) {
      try {
        localStorage.removeItem(LOCAL_KEY)
      } catch {
        // 同上，失敗無害
      }
    }
  }

  async function load(): Promise<void> {
    if (!auth.isAuthenticated) {
      local.value = readLocal()
      return
    }
    loading.value = true
    try {
      remote.value = await request<CartView>('/api/v1/cart', { authenticated: true })
    } finally {
      loading.value = false
    }
  }

  async function addItem(skuId: number, quantity: number): Promise<void> {
    if (auth.isAuthenticated) {
      remote.value = await request<CartView>('/api/v1/cart/items', {
        method: 'POST', authenticated: true, body: { skuId, quantity },
      })
      return
    }

    // 未登入：本地累加，語意與伺服器端一致
    const items = [...local.value]
    const existing = items.find((item) => item.skuId === skuId)
    if (existing) {
      existing.quantity = Math.min(existing.quantity + quantity, MAX_QUANTITY)
    } else {
      if (items.length >= MAX_ITEMS) {
        throw new Error(`購物車最多放 ${MAX_ITEMS} 種商品`)
      }
      items.push({ skuId, quantity: Math.min(quantity, MAX_QUANTITY) })
    }
    local.value = items
    writeLocal(items)
  }

  async function changeQuantity(skuId: number, quantity: number): Promise<void> {
    if (auth.isAuthenticated) {
      remote.value = await request<CartView>(`/api/v1/cart/items/${skuId}`, {
        // 只送 quantity——skuId 在路徑上。兩邊都送會製造
        // 「不一致時聽誰的」這個沒有答案的問題
        method: 'PUT', authenticated: true, body: { quantity },
      })
      return
    }
    const items = quantity === 0
      ? local.value.filter((item) => item.skuId !== skuId)
      : local.value.map((item) =>
          item.skuId === skuId ? { ...item, quantity } : item)
    local.value = items
    writeLocal(items)
  }

  async function removeItem(skuId: number): Promise<void> {
    if (auth.isAuthenticated) {
      remote.value = await request<CartView>(`/api/v1/cart/items/${skuId}`, {
        method: 'DELETE', authenticated: true,
      })
      return
    }
    const items = local.value.filter((item) => item.skuId !== skuId)
    local.value = items
    writeLocal(items)
  }

  /**
   * 登入後把本地購物車併進伺服器端。
   *
   * **合併成功才清掉本地**——順序反了的話，合併請求失敗時
   * 使用者的購物車就兩邊都沒有了。
   *
   * 本地是空的就直接載入伺服器端，不必多送一次請求。
   */
  async function mergeAfterLogin(): Promise<void> {
    const items = readLocal()
    if (items.length === 0) {
      await load()
      return
    }
    remote.value = await request<CartView>('/api/v1/cart/merge', {
      method: 'POST', authenticated: true, body: { items },
    })
    clearLocal()
  }

  /** 結帳成功後清空。 */
  function reset(): void {
    remote.value = null
    clearLocal()
  }

  return {
    remote: readonly(remote),
    local: readonly(local),
    loading: readonly(loading),
    itemCount,
    load,
    addItem,
    changeQuantity,
    removeItem,
    mergeAfterLogin,
    reset,
  }
})
