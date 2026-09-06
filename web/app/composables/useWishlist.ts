import type { ApiResponse, ProductView } from '~/types/api'

/**
 * 收藏。
 *
 * 未登入時按愛心不報錯，而是把它當成登入的入口——
 * 直接跳出「請先登入」比一個沉默失敗的按鈕好。
 */
export function useWishlist() {
  const { request } = useApi()
  const auth = useAuthStore()

  /** 這一批商品裡哪些已收藏。列表一次問完，不逐張卡片查。 */
  const wishlisted = useState<Set<number>>('wishlisted', () => new Set())

  async function loadStatus(productIds: number[]) {
    if (!auth.isAuthenticated || productIds.length === 0) {
      return
    }
    try {
      const ids = await request<number[]>(
        `/api/v1/wishlist/among?productIds=${[...new Set(productIds)].join(',')}`,
        { authenticated: true })
      wishlisted.value = new Set(ids)
    } catch {
      // fail-open：查不到就當作沒收藏，愛心是空的。不該讓列表因此壞掉
      wishlisted.value = new Set()
    }
  }

  function isWishlisted(productId: number): boolean {
    return wishlisted.value.has(productId)
  }

  /** 回傳切換後的狀態。先改本地再送請求，失敗時退回——愛心必須是即時的。 */
  async function toggle(productId: number): Promise<boolean> {
    if (!auth.isAuthenticated) {
      throw new Error('請先登入才能收藏')
    }
    const next = !isWishlisted(productId)
    const snapshot = new Set(wishlisted.value)
    const optimistic = new Set(wishlisted.value)
    if (next) {
      optimistic.add(productId)
    } else {
      optimistic.delete(productId)
    }
    wishlisted.value = optimistic

    try {
      await request<void>(`/api/v1/wishlist/${productId}`,
        { method: next ? 'POST' : 'DELETE', authenticated: true })
      return next
    } catch (cause) {
      wishlisted.value = snapshot
      throw cause
    }
  }

  async function list(page = 0, size = 20) {
    return await request<{ items: ProductView[], total: number }>(
      `/api/v1/wishlist?page=${page}&size=${size}`, { authenticated: true })
  }

  return { wishlisted, loadStatus, isWishlisted, toggle, list }
}
