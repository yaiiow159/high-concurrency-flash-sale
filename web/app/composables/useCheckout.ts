import { useApi } from '~/composables/useApi'
import type { OrderDetailView } from '~/types/api'

export type CheckoutState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'placed'; order: OrderDetailView }
  | { kind: 'failed'; code: string; message: string }

/**
 * 一般下單。
 *
 * 與 `useSeckill` 的差別是這裡**沒有輪詢**——一般下單是同步的，
 * 回應到手時訂單已經成立。秒殺那套「送出後不斷問訂單好了沒」
 * 是為最終一致付出的代價，這條通道不需要付，就不該付。
 *
 * requestId 在**送出前**產生並保留：網路逾時後重送同一個值，
 * 後端會回同一張訂單而不是下第二單。若每次重試都換一個新的，
 * 使用者按兩次就會買到兩份。
 */
export function useCheckout() {
  const { request } = useApi()

  const state = ref<CheckoutState>({ kind: 'idle' })

  /**
   * 這一輪結帳的冪等鍵。
   *
   * 只在成功之後才作廢——失敗重試必須沿用同一個值，
   * 因為「失敗」有可能只是回應在路上掉了，訂單其實已經建立。
   */
  let requestId: string | null = null

  async function place(items: Array<{ skuId: number; quantity: number }>): Promise<void> {
    if (state.value.kind === 'submitting') {
      return
    }
    requestId ??= crypto.randomUUID()
    state.value = { kind: 'submitting' }

    try {
      const order = await request<OrderDetailView>('/api/v1/orders', {
        method: 'POST',
        authenticated: true,
        body: { items, requestId },
      })
      requestId = null
      state.value = { kind: 'placed', order }
    } catch (error) {
      const failure = error as { code?: string; message?: string }
      state.value = {
        kind: 'failed',
        code: failure.code ?? 'UNKNOWN',
        message: failure.message ?? '下單失敗，請稍後再試',
      }
    }
  }

  function reset(): void {
    state.value = { kind: 'idle' }
    requestId = null
  }

  return { state: readonly(state), place, reset }
}
