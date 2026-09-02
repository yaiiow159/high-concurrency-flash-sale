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
  /** 指向真實 SKU，不是 SPU——特價的一定是某個具體規格 */
  skuId: number
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

/** 訂單行。多品項重構後，訂單的商品資訊移到這裡（ADR-0007）。 */
export interface OrderLine {
  skuId: number
  /** 下單當下的商品名稱快照——商家改名後歷史訂單不該跟著變 */
  skuSnapshot: string
  unitPrice: number
  quantity: number
  subtotal: number
}

/** 訂單裡的收貨資訊快照。秒殺訂單為 null——那條通道下單當下不收集地址。 */
export interface OrderShippingView {
  recipientName: string
  phone: string
  postalCode: string
  region: string
  district: string
  streetAddress: string
  fullAddress: string
}

export interface OrderView {
  orderNo: string
  userId: number | null
  channel: string | null
  lines: OrderLine[]
  totalAmount: number | null
  shipping: OrderShippingView | null
  status: string
  closeReason: string | null
  createdAt: string | null
  paidAt: string | null
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

// ---------------------------------------------------------------------------
// 商品目錄
//
// 這些型別目前是手寫的，會與後端漂移——實測已經發生過一次
// （ActivityView.productId 改成 skuId 時，這裡安靜地留在舊欄位上）。
// 下一步應改由 OpenAPI 產生，讓契約變動在編譯期就失敗。
// ---------------------------------------------------------------------------

export interface SkuView {
  skuId: number
  /** 規格屬性，保序。後端以 LinkedHashMap 序列化，JS 物件也保留插入順序 */
  spec: Record<string, string>
  specDisplay: string
  price: number
  purchasable: boolean
}

export interface ProductView {
  productId: number
  categoryId: number
  name: string
  brand: string | null
  /** 列表回應為精簡版，這兩個欄位只有詳情才有 */
  description: string | null
  status: string
  /** 列表顯示「NT$ 990 起」用的最低 SKU 價 */
  lowestPrice: number
  skus: SkuView[]
}

export interface CategoryView {
  categoryId: number
  name: string
  level: number
  children: CategoryView[]
}

// ---------------------------------------------------------------------------
// 一般下單
//
// 訂單只有 OrderView 一個型別。先前這裡另外有一份 OrderDetailView，
// 描述的是同一個後端回應——兩份手寫型別對一個契約，
// 遲早會有一份忘了跟著改，而那正是這個檔案開頭警告過的漂移。
// ---------------------------------------------------------------------------

/** 下單請求。刻意沒有價格欄位——價格由目錄決定，不由呼叫端指定。 */
export interface PlaceOrderRequest {
  items: Array<{ skuId: number; quantity: number }>
  requestId: string
  /** 訂單存的是這筆地址的**快照**，不是這個 ID */
  addressId: number
}

// ---------------------------------------------------------------------------
// 收貨地址
// ---------------------------------------------------------------------------

export interface AddressView {
  addressId: number
  recipientName: string
  phone: string
  postalCode: string
  region: string
  district: string
  streetAddress: string
  /** 後端組好的完整地址，前端不自己拼——拼法在兩邊漂移就會顯示不一致 */
  fullAddress: string
  defaultAddress: boolean
}

export type AddressPayload = Omit<AddressView, 'addressId' | 'fullAddress'>

// ---------------------------------------------------------------------------
// 購物車
//
// 品項沒有價格欄位——伺服器每次回傳當下的目錄價。
// 購物車回答的是「現在買要多少錢」，存快照會在商家調價後變成謊言。
// 這與訂單行必須存快照剛好相反，兩者不可互換。
// ---------------------------------------------------------------------------

export interface CartItemView {
  skuId: number
  productId: number
  productName: string
  specDisplay: string
  /** 當下的目錄價，僅供預覽；真正的金額在下單時凍結進訂單 */
  unitPrice: number
  quantity: number
  subtotal: number
  /** 已下架的品項會留在清單裡並標記為 false，不會靜默消失 */
  purchasable: boolean
}

export interface CartView {
  items: CartItemView[]
  /** 不含已下架品項——顯示一個結不了帳的金額只會造成誤解 */
  totalAmount: number
  totalQuantity: number
  /** 本次查詢中因商品已被刪除而移除的品項數，必須告訴使用者 */
  removedCount: number
}
