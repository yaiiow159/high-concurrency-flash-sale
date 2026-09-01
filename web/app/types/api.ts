/**
 * 後端 API 的型別。
 *
 * 目前手寫。正式的做法是從 springdoc 的 `/v3/api-docs` 以
 * `openapi-typescript` 自動產生，並在 CI 檢查產物有無 diff——
 * 讓後端改欄位時前端**編譯就失敗**，而不是上線後才在 console 看到 undefined。
 *
 * 這件事已列在 roadmap 的反模式清單（前後端契約漂移）。
 */

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  retryable: boolean
}

export interface ActivityView {
  activityId: number
  productId: number
  productName: string
  seckillPrice: number
  totalStock: number
  availableStock: number
  perUserLimit: number
  startAt: string
  endAt: string
  status: string
  purchasable: boolean
  /** 伺服器當下時間，供前端校正本地時鐘 */
  serverTime: string
}

export interface SeckillTicket {
  orderNo: string
  message: string
}

export interface OrderView {
  orderNo: string
  activityId: number | null
  userId: number | null
  quantity: number | null
  amount: number | null
  status: string
  closeReason: string | null
  createdAt: string | null
  /** true 代表庫存已扣、訂單仍在非同步建立中，前端應繼續輪詢 */
  processing: boolean
}

export interface PaymentIntentView {
  paymentNo: string
  orderNo: string
  paymentUrl: string
  status: string
}

/** 搶購的最終結果，供 UI 決定要顯示什麼。 */
export type SeckillOutcome =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'processing'; orderNo: string }
  | { kind: 'success'; orderNo: string; order: OrderView }
  | { kind: 'timeout'; orderNo: string }
  | { kind: 'rejected'; code: string; message: string }
