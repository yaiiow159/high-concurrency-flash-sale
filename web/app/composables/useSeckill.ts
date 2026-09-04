import { useApi, ApiError } from '~/composables/useApi'
import type { ActivityView, OrderView, SeckillOutcome, SeckillTicket } from '~/types/api'

/**
 * 訂單輪詢的分層節奏。
 *
 * 剛送出時消費端多半在數百毫秒內就落庫，因此前段密集；
 * 之後拉長間隔避免對後端造成不必要的壓力。
 *
 * **必須有上限。** 無限輪詢在尖峰時會變成第二波流量——
 * 而且是打在系統已經很吃力的時候。超過上限就請使用者去訂單頁看，
 * 那是一次由使用者主導、分散在時間軸上的查詢。
 */
const ORDER_POLL_SCHEDULE = [
  { untilMillis: 10_000, intervalMillis: 1_000 },
  { untilMillis: 30_000, intervalMillis: 3_000 },
] as const

const ORDER_POLL_TIMEOUT_MILLIS = 30_000

/** 有庫存時的輪詢間隔。售罄後停止——再問也不會變。 */
const STOCK_POLL_INTERVAL_MILLIS = 2_000

export function useSeckill(activityId: number) {
  const { request } = useApi()
  const { calibrate, now } = useServerTime()

  const activity = ref<ActivityView | null>(null)
  const outcome = ref<SeckillOutcome>({ kind: 'idle' })
  const submitting = ref(false)

  let stockTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 以 SSR 取得的活動作為初始畫面，避免首屏空白。
   *
   * 存在的理由是 `activity` 對外是 readonly——只有這個 composable
   * 能改它的狀態。頁面若能直接賦值，庫存輪詢與使用者操作就多了一個
   * 不受控的寫入點，而那正是「畫面數字和實際庫存對不上」的來源。
   *
   * <b>不覆寫已載入的資料</b>：SSR 那份可能來自 ISR 快取，
   * 有可能比客戶端剛抓到的還舊。
   */
  function seedFromServerRender(view: ActivityView): void {
    if (activity.value === null) {
      activity.value = view
    }
  }

  /** 讀取活動，並順手校正時鐘偏移。 */
  async function loadActivity(): Promise<void> {
    const sentAt = Date.now()
    const view = await request<ActivityView>(`/api/v1/activities/${activityId}`)
    calibrate(view.serverTime, sentAt, Date.now())
    activity.value = view
  }

  /**
   * 庫存輪詢。
   *
   * 售罄後停止：再問也不會變，而售罄正是流量最大的時刻——
   * 此時每個瀏覽器都在輪詢，等於對自己發動一次攻擊。
   */
  function startStockPolling(): void {
    stopStockPolling()
    const tick = async () => {
      try {
        await loadActivity()
      } catch {
        // 庫存查詢失敗不影響主流程，靜默重試即可
      }
      if ((activity.value?.availableStock ?? 0) > 0) {
        stockTimer = setTimeout(tick, STOCK_POLL_INTERVAL_MILLIS)
      }
    }
    stockTimer = setTimeout(tick, STOCK_POLL_INTERVAL_MILLIS)
  }

  function stopStockPolling(): void {
    if (stockTimer) {
      clearTimeout(stockTimer)
      stockTimer = null
    }
  }

  /**
   * 發起搶購。
   *
   * `requestId` 由前端產生，是整條鏈路的冪等鍵——
   * 網路逾時後重送相同的值，後端只會扣一次庫存並回傳同一張訂單。
   */
  async function attempt(quantity = 1): Promise<void> {
    if (submitting.value) {
      return
    }
    submitting.value = true
    outcome.value = { kind: 'submitting' }

    const requestId = crypto.randomUUID()
    try {
      const ticket = await request<SeckillTicket>('/api/v1/seckill/orders', {
        method: 'POST',
        authenticated: true,
        body: { activityId, quantity, requestId },
      })
      outcome.value = { kind: 'processing', orderNo: ticket.orderNo }
      await pollOrder(ticket.orderNo)
    } catch (error) {
      const apiError = error instanceof ApiError
        ? error
        : new ApiError('UNKNOWN', '發生未預期的錯誤', 0, false)
      outcome.value = { kind: 'rejected', code: apiError.code, message: apiError.message }
    } finally {
      submitting.value = false
      // 搶購結束後庫存必然變動，立刻刷新一次讓數字即時
      loadActivity().catch(() => undefined)
    }
  }

  /**
   * 輪詢訂單直到落庫或逾時。
   *
   * 後端在訂單尚未落庫時回 `processing: true` 而非 404——
   * 這個區別很重要：404 會讓使用者以為沒搶到，
   * 但庫存其實已經是他的了。
   */
  async function pollOrder(orderNo: string): Promise<void> {
    const startedAt = Date.now()

    while (Date.now() - startedAt < ORDER_POLL_TIMEOUT_MILLIS) {
      await sleep(intervalFor(Date.now() - startedAt))
      try {
        const order = await request<OrderView>(`/api/v1/seckill/orders/${orderNo}`, {
          authenticated: true,
        })
        if (!order.processing) {
          outcome.value = { kind: 'success', orderNo, order }
          return
        }
        // 每次輪詢都把排隊資訊更新上去（ADR-0023）。
        // 只顯示「處理中」的話，使用者分不出是三秒還是四十分鐘，
        // 而分不出來的時候他會以為系統壞了
        outcome.value = { kind: 'processing', orderNo, queue: order.queue }
      } catch (error) {
        // 訂單建立失敗（例如進了 DLQ）時後端會回 ORDER_NOT_FOUND 並帶原因
        if (error instanceof ApiError && error.code === 'B0007') {
          outcome.value = { kind: 'rejected', code: error.code, message: error.message }
          return
        }
      }
    }
    outcome.value = { kind: 'timeout', orderNo }
  }

  function intervalFor(elapsedMillis: number): number {
    const stage = ORDER_POLL_SCHEDULE.find(s => elapsedMillis < s.untilMillis)
    return stage?.intervalMillis ?? 3_000
  }

  function sleep(millis: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, millis))
  }

  function reset(): void {
    outcome.value = { kind: 'idle' }
  }

  onUnmounted(stopStockPolling)

  return {
    activity: readonly(activity),
    outcome: readonly(outcome),
    submitting: readonly(submitting),
    serverNow: now,
    seedFromServerRender,
    loadActivity,
    startStockPolling,
    stopStockPolling,
    attempt,
    reset,
  }
}
