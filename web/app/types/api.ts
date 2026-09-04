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
  /** 定價小計（單價 × 數量），折扣前 */
  subtotal: number
  /**
   * 整單折扣分攤後，這一行**實際付了多少**。
   *
   * 退貨頁要顯示的是這個數字而不是 subtotal——
   * 使用者退一件商品拿回的錢，是他當初為那一件付的錢。
   */
  paidAmount: number
}

/**
 * 訂單上的一筆折扣快照。
 *
 * 存明細而不是一個總額：使用者問的是「為什麼折了 2000」，
 * 而那需要知道是哪幾個優惠、各折了多少（ADR-0013 決策 3）。
 */
export interface OrderDiscount {
  sourceType: string
  sourceId: number | null
  name: string
  amount: number
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
  /** 各行小計的加總，**折扣前** */
  subtotal: number | null
  discounts: OrderDiscount[]
  /** **商品**折後應付。**不含運費**——實付是 payableAmount */
  totalAmount: number | null
  /** 已扣掉免運折抵的實收運費。秒殺訂單為 0（那條通道不收地址）*/
  shippingFee: number | null
  /** 這張訂單總共付了多少 = totalAmount + shippingFee。付款與退款上限以它為準 */
  payableAmount: number | null
  shippingMethod: string | null
  shipping: OrderShippingView | null
  status: string
  closeReason: string | null
  createdAt: string | null
  paidAt: string | null
  /** true 代表庫存已扣、訂單仍在非同步建立中，前端應繼續輪詢 */
  processing: boolean
  /** 仍在佇列中時的排隊資訊（ADR-0023）；訂單已建立時為 null */
  queue: OrderQueue | null
}

/**
 * 商品列表的一頁（keyset 分頁，ADR-0021）。
 *
 * `nextCursor` 由伺服器給，前端**原樣送回**即可——不要自己從
 * items 取最後一筆的 id，那等於把伺服器的排序鍵寫死在前端。
 */
/**
 * SKU 庫存。
 *
 * `available` 只在低於門檻時才有值——庫存量是商業情報，
 * 但「剩 3 件」對使用者是真實的購買訊號。
 */
export interface SkuStockView {
  skuId: number
  inStock: boolean
  lowStock: boolean
  /** 充足時**整個欄位不會出現**（後端省略 null），因此是選填而不是 `number | null` */
  available?: number
}

export interface ProductPage {
  items: ProductView[]
  /** null 代表沒有下一頁。字串——依價格或銷量排序時是 `排序值:id` 的複合值 */
  nextCursor: string | null
  hasMore: boolean
}

export interface PaymentIntentView {
  paymentNo: string
  orderNo: string
  paymentUrl: string
  status: string
}

/**
 * 排隊資訊。
 *
 * `estimatedWaitSeconds` 為 -1 代表**算不出來**，不是「不用等」——
 * 顯示成「約 0 秒」然後讓人等四十分鐘，比誠實說不知道更糟。
 */
export interface OrderQueue {
  ahead: number
  estimatedWaitSeconds: number
}

/** 搶購的最終結果，供 UI 決定要顯示什麼。 */
export type SeckillOutcome =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'processing'; orderNo: string; queue?: OrderQueue | null }
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
  /** 要使用的優惠券。只送 ID——折多少由伺服器算，前端說了不算 */
  couponId?: number | null
}

// ---------------------------------------------------------------------------
// 優惠
// ---------------------------------------------------------------------------

/** 使用者手上一張還能用的券。折抵規則來自它引用的 promotion。 */
export interface CouponView {
  id: number
  code: string
  name: string
  /** FIXED_AMOUNT 或 PERCENTAGE */
  rule: string
  threshold: number
  /** 固定折抵金額，或折扣率（0.2 = 折 20%） */
  value: number
  maxDiscount: number | null
  expiresAt: string
}

/**
 * 結帳試算。
 *
 * 由**伺服器**算，前端只負責顯示。前端自己算折扣是錯的——
 * 兩邊算出不同答案時，使用者只會相信他先看到的那一個。
 */
export interface CheckoutPreview {
  subtotal: number
  discounts: OrderDiscount[]
  totalDiscount: number
  /** **商品**折後應付，不含運費 */
  payable: number
  /** 已扣掉免運折抵的實收運費 */
  shippingFee: number
  /**
   * 有沒有足夠資訊算運費（選了地址沒有）。
   *
   * false 時 shippingFee 是 0，但那**不是免運**而是「還算不出來」——
   * 畫面要說得出差別，否則使用者會以為免運然後在下一步被多收錢。
   */
  shippingKnown: boolean
  /** 推導出來的區域名稱，用來解釋「為什麼這一單運費比較貴」 */
  shippingZone: string | null
  /** 總計 = payable + shippingFee。這才是要付的錢 */
  total: number
  lines: Array<{
    skuId: number
    skuSnapshot: string
    unitPrice: number
    quantity: number
    subtotal: number
    paidAmount: number
  }>
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

// ---------------------------------------------------------------------------
// 履約
// ---------------------------------------------------------------------------

export interface ShipmentView {
  shipmentNo: string
  orderNo: string
  carrier: string | null
  carrierName: string | null
  trackingNumber: string | null
  /** 由承運商列舉算出；沒有外部查詢系統的承運商為 null 而非假連結 */
  trackingUrl: string | null
  status: string
  failureReason: string | null
  /** 大於 1 代表曾配送失敗後重送 */
  dispatchCount: number
  shippedAt: string | null
  deliveredAt: string | null
}

// ---------------------------------------------------------------------------

/** 退貨原因。責任歸屬由原因決定，不另開欄位。 */
export type ReturnReason =
  | 'DEFECTIVE'
  | 'NOT_AS_DESCRIBED'
  | 'WRONG_ITEM'
  | 'CHANGED_MIND'
  | 'OTHER'

export interface ReturnLineView {
  skuId: number
  /** 下單當下的商品名稱快照，與訂單行同一份 */
  skuSnapshot: string
  unitPrice: number
  quantity: number
  /**
   * 驗收結果。尚未驗收時後端會省略這個欄位，因此是 optional；
   * 明確的 false 才代表「驗收過且判定不可再售」。
   */
  restockable?: boolean | null
}

/**
 * 退貨單。
 *
 * <b>時間戳記宣告成 optional 而不是 `string | null`</b>，因為後端序列化時
 * 會把 null 欄位整個省略——收到的是 `undefined` 而不是 `null`。
 * 宣告成 `string | null` 的話，`x !== null` 這種嚴格比較會對 `undefined` 回 true，
 * 於是「還沒發生的步驟」全部被當成已完成。這個 bug 真的發生過：
 * 一張還在待審核的退貨單，進度條三個階段全亮。
 */
export interface ReturnRequestView {
  returnNo: string
  orderNo: string
  status: string
  reason: ReturnReason
  reasonDetail?: string | null
  /** 未出貨的訂單為 false——貨還在倉庫，沒有東西要寄回 */
  requiresGoodsReturn: boolean
  /** 由退貨行的快照單價算出。前端不自己乘一次，畫面與實際退款必須是同一個數字 */
  refundAmount: number
  lines: ReturnLineView[]
  reviewNote?: string | null
  createdAt: string
  reviewedAt?: string | null
  receivedAt?: string | null
  refundedAt?: string | null
}

/**
 * 這張訂單現在能退什麼。
 *
 * 可退數量由後端算——「審核中的退貨單也佔用額度」是領域規則，
 * 前端再實作一次的話，症狀會是「畫面說可以退，送出卻被拒絕」。
 */
export interface ReturnableLineView {
  skuId: number
  skuSnapshot: string
  unitPrice: number
  orderedQuantity: number
  /** 為 0 代表這一項已全部申請過，畫面應標成不可選 */
  returnableQuantity: number
  /**
   * 整單折扣分攤後，這一行**實際付了多少**。
   *
   * 預估退款要用它算——有折扣的訂單，unitPrice 是使用者沒有付過的錢。
   */
  paidAmount: number
}

export interface ReturnableView {
  orderNo: string
  returnable: boolean
  /** 不可退時的原因，直接顯示給使用者；可退時後端省略此欄位 */
  reason?: string | null
  requiresGoodsReturn: boolean
  lines: ReturnableLineView[]
}

/** 送出的欄位。requestId 由 useReturns.open 另外帶入，不放在這裡以免被漏掉。 */
export interface OpenReturnPayload {
  items: { skuId: number, quantity: number }[]
  reason: ReturnReason
  reasonDetail?: string
}

// ---------------------------------------------------------------------------

/** 通知類型。只涵蓋會改變使用者預期的里程碑，不是每個領域事件都在這裡。 */
export type NotificationType =
  | 'ORDER_PAID'
  | 'ORDER_SHIPPED'
  | 'ORDER_COMPLETED'
  | 'ORDER_CANCELLED'
  | 'REFUND_SENT'

/**
 * 站內信。
 *
 * `title` 與 `body` 是後端在建立當下算好的**快照**，前端不做任何字串組裝——
 * 在這裡拼字串等於讓「我們對使用者說過什麼」有第二個版本，
 * 而客訴時只有後端那份算數。
 */
export interface NotificationView {
  notificationId: number
  type: NotificationType
  title: string
  body: string
  /** 關聯的訂單號或退貨單號；後端在沒有關聯時會省略這個欄位 */
  referenceNo?: string | null
  unread: boolean
  createdAt: string
}

// ---------------------------------------------------------------------------

/**
 * 搜尋結果（ADR-0012）。
 *
 * <b>沒有庫存欄位</b>，那是刻意的：索引的同步延遲是數秒，
 * 而庫存每秒都在變，放進來只會顯示一個必定過時的數字。
 * 價格是索引當下的快照，點進商品頁後會重新從 Catalog 讀。
 */
export interface ProductSearchHit {
  productId: number
  name: string
  brand: string | null
  categoryId: number | null
  lowestPrice: number
}

export interface ProductSearchResult {
  hits: ProductSearchHit[]
  total: number
  /** 品牌 → 筆數。降級時為空物件——那條路徑不做分面，湊一份出來只會是錯的 */
  facets: Record<string, number>
  /** true 代表搜尋引擎故障、這份結果來自資料庫的模糊比對，沒有相關性排序 */
  degraded: boolean
}

// ---------------------------------------------------------------------------
// 評價
//
// 星等分佈的百分比由**後端**算好。前端拿 count 自己除的話，
// 兩邊的四捨五入遲早會不同，而那會表現成「長條加起來不是 100%」。
// ---------------------------------------------------------------------------

/** 一則公開的評價。**刻意沒有 userId**——帶上它等於讓人把某個 ID 的消費紀錄串起來看。 */
export interface ReviewView {
  reviewId: number
  productId: number
  skuId: number
  /** 已由伺服器遮蔽（王＊＊）。完整姓名根本不在資料庫裡 */
  authorName: string
  stars: number
  content: string
  createdAt: string
  /** 被改過。畫面要標出來——讀者有權知道這不是原始版本 */
  edited: boolean
  /** 還在七天修改窗口內。由伺服器判斷，前端不自己拿 createdAt 算 */
  editable: boolean
}

export interface RatingBucket {
  stars: number
  count: number
  /** 已算好的百分比（0–100），直接拿去畫長條寬度 */
  percentage: number
}

export interface ProductRatingView {
  productId: number
  /** 小數一位。count 為 0 時是 0，畫面應顯示「尚無評價」而不是「0 分」 */
  average: number
  count: number
  /** 由高星到低星 */
  distribution: RatingBucket[]
}

/** 這張訂單現在能評什麼。可評項目由後端算，前端不自己比對。 */
export interface ReviewableView {
  orderNo: string
  reviewable: boolean
  /** 不能評時的原因，直接顯示；可評時後端省略此欄位 */
  reason?: string | null
  lines: Array<{
    skuId: number
    skuSnapshot: string
    /** false 代表已評價過，畫面應標成已評價而不是隱藏 */
    pending: boolean
  }>
}

/** 發表與修改共用。沒有 authorName——那是伺服器從帳號取出並遮蔽的。 */
export interface WriteReviewPayload {
  skuId?: number
  stars: number
  content: string
}

// ---------------------------------------------------------------------------
// 會員
//
// 升級進度與「換不換得起」都由**後端**算好。前端拿門檻自己內插，
// 會在「剛升級」與「已達頂級」兩個邊界算出 NaN 或超過 100 的值，
// 而那會直接畫成一條超出容器的長條。
// ---------------------------------------------------------------------------

export interface MemberProfileView {
  userId: number
  /** 代號，供前端對應樣式 */
  tier: string
  tierName: string
  /** 這個等級的積分回饋倍率。顯示出來讓「升級」有一個算得出來的價值 */
  multiplier: number
  pointBalance: number
  /** 餘額為負：退貨扣回時使用者已經把點花掉了。那是真實的債務，不是錯誤 */
  inDebt: boolean
  cumulativeSpend: number
  /** 已是最高等級時為 null——畫面要顯示「已達最高等級」而不是「還差 0 元」 */
  nextTier: string | null
  nextTierName: string | null
  amountToNextTier: number
  /** 0–100，已由後端夾住 */
  progressToNextTier: number
}

export interface PointTransactionView {
  id: number
  /** 正數為入帳、負數為扣回 */
  delta: number
  /** 這一筆之後的餘額。讓使用者不必自己從最新餘額往回加 */
  balanceAfter: number
  reason: string
  /** 已經翻好的中文。翻譯放後端——同一組代號也會出現在後台與客服工具上 */
  reasonName: string
  refNo: string
  createdAt: string
}

export interface ExchangeableCouponView {
  promotionId: number
  name: string
  rule: string
  threshold: number
  value: number
  maxDiscount: number | null
  pointCost: number
  /** 目前餘額換不換得起。由後端算，避免三處各判斷一次而有一處寫成 > */
  affordable: boolean
}

export interface ExchangeResult {
  couponCode: string
  promotionName: string
  pointsSpent: number
  balanceAfter: number
}
