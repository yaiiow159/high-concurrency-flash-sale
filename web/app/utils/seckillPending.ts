/** 一次還沒有定論的搶購。`orderNo` 有值代表已受理、還在等訂單落庫。 */
export interface PendingAttempt {
  requestId: string
  orderNo: string | null
  startedAt: number
}

/** 超過這個時間就不再接回：訂單早該有結果了，去訂單頁看比在這裡重新輪詢合理。 */
export const PENDING_MAX_AGE_MILLIS = 30 * 60 * 1000

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

export function pendingKey(activityId: number): string {
  return `seckill:pending:${activityId}`
}

/** 讀不到、格式不對、過期都當成沒有——寧可讓使用者重按，也不要接回一筆壞掉的狀態。 */
export function readPending(store: Store, activityId: number, now: number): PendingAttempt | null {
  try {
    const raw = store.getItem(pendingKey(activityId))
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Partial<PendingAttempt>
    if (typeof parsed.requestId !== 'string' || typeof parsed.startedAt !== 'number') {
      return null
    }
    if (now - parsed.startedAt > PENDING_MAX_AGE_MILLIS) {
      store.removeItem(pendingKey(activityId))
      return null
    }
    return {
      requestId: parsed.requestId,
      orderNo: typeof parsed.orderNo === 'string' ? parsed.orderNo : null,
      startedAt: parsed.startedAt,
    }
  } catch {
    return null
  }
}

export function writePending(store: Store, activityId: number, attempt: PendingAttempt): void {
  try {
    store.setItem(pendingKey(activityId), JSON.stringify(attempt))
  } catch {
    // 私密視窗可能不允許寫入；這一頁內仍然正常，只是重整後接不回來
  }
}

export function clearPending(store: Store, activityId: number): void {
  try {
    store.removeItem(pendingKey(activityId))
  } catch {
    // 同上
  }
}

/**
 * 這個錯誤是否代表「不知道送到沒」。是的話冪等鍵必須留著——
 * 下一次按下去要沿用同一個 requestId，後端才認得出是同一筆。
 */
export function deliveryUnknown(code: string, status: number): boolean {
  return code === 'NETWORK' || code === 'UNKNOWN' || status === 0 || status >= 500
}
