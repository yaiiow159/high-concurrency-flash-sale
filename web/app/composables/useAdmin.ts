import { useApi } from '~/composables/useApi'
import type {
  ActivityView,
  ProductView,
  ReturnRequestView,
  ShipmentView,
} from '~/types/api'

/**
 * 搜尋索引對帳結果。
 *
 * 欄位對齊後端的 `SearchIndexReconciliation`——**手寫兩份型別對一個契約，
 * 遲早會有一份忘了跟著改**，而症狀是畫面上一片 undefined 而不是型別錯誤。
 *
 * @property missing 在資料庫是上架、但索引裡沒有的商品（搜不到）
 * @property orphaned 索引裡有、但資料庫已非上架的商品（搜得到卻買不到）
 */
export interface SearchReconciliationView {
  indexedCount: number
  onShelfCount: number
  missing: number[]
  orphaned: number[]
  repaired: number
  balanced: boolean
}

/**
 * 後台的 API。
 *
 * 集中在一處而不是各頁自己拼網址：後台的每一支都在 `/api/v1/admin` 底下
 * 且都要帶令牌，散開來寫遲早會有一頁忘了 `authenticated: true`，
 * 而那個症狀是 401 而不是任何看得懂的錯誤。
 *
 * **這裡沒有任何權限判斷。** 授權全部在後端；前端能做的只有
 * 「打了會不會成功」，而那件事後端已經回答了（ADR-0015 決策 2）。
 */
export function useAdmin() {
  const { request } = useApi()

  const auth = { authenticated: true } as const

  // ---- 出貨 ----

  function shipments(status: string, limit = 50) {
    return request<ShipmentView[]>(
      `/api/v1/admin/shipments?status=${status}&limit=${limit}`, auth)
  }

  function dispatch(orderNo: string, carrier: string, trackingNumber: string) {
    return request<ShipmentView>(`/api/v1/admin/shipments/${orderNo}/dispatch`, {
      ...auth, method: 'POST', body: { carrier, trackingNumber },
    })
  }

  function markDelivered(orderNo: string) {
    return request<ShipmentView>(`/api/v1/admin/shipments/${orderNo}/delivered`,
      { ...auth, method: 'POST' })
  }

  function markFailed(orderNo: string, reason: string) {
    return request<ShipmentView>(
      `/api/v1/admin/shipments/${orderNo}/failed?reason=${encodeURIComponent(reason)}`,
      { ...auth, method: 'POST' })
  }

  // ---- 退貨 ----

  function returns(status: string, limit = 50) {
    return request<ReturnRequestView[]>(
      `/api/v1/admin/returns?status=${status}&limit=${limit}`, auth)
  }

  /** 核准。note 是給客服自己看的備註，可留空。 */
  function approveReturn(returnNo: string, note = '') {
    return request<ReturnRequestView>(`/api/v1/admin/returns/${returnNo}/approve`, {
      ...auth, method: 'POST', body: { note },
    })
  }

  /** 駁回。**理由必填**——使用者會看到它，一句空白的駁回等於沒有回覆。 */
  function rejectReturn(returnNo: string, note: string) {
    return request<ReturnRequestView>(`/api/v1/admin/returns/${returnNo}/reject`, {
      ...auth, method: 'POST', body: { note },
    })
  }

  // ---- 商品 ----

  function products(status: string, page = 0, size = 20) {
    const query = status ? `&status=${status}` : ''
    return request<ProductView[]>(
      `/api/v1/admin/products?page=${page}&size=${size}${query}`, auth)
  }

  function createProduct(body: unknown) {
    return request<ProductView>('/api/v1/admin/products', { ...auth, method: 'POST', body })
  }

  function putOnShelf(productId: number) {
    return request<ProductView>(`/api/v1/admin/products/${productId}/on-shelf`,
      { ...auth, method: 'POST' })
  }

  function takeOffShelf(productId: number) {
    return request<ProductView>(`/api/v1/admin/products/${productId}/off-shelf`,
      { ...auth, method: 'POST' })
  }

  // ---- 活動 ----

  function activities(page = 0, size = 50) {
    return request<ActivityView[]>(
      `/api/v1/admin/activities?page=${page}&size=${size}`, auth)
  }

  function publishActivity(activityId: number) {
    return request<ActivityView>(`/api/v1/admin/activities/${activityId}/online`,
      { ...auth, method: 'POST' })
  }

  function offlineActivity(activityId: number) {
    return request<ActivityView>(`/api/v1/admin/activities/${activityId}/offline`,
      { ...auth, method: 'POST' })
  }

  function warmUp(activityId: number, force = false) {
    return request<unknown>(`/api/v1/activities/${activityId}/warm-up?force=${force}`,
      { ...auth, method: 'POST' })
  }

  // ---- 維運 ----

  function reindex() {
    return request<{ indexed: number }>('/api/v1/admin/search/reindex',
      { ...auth, method: 'POST' })
  }

  function searchReconciliation(repair = false) {
    return request<SearchReconciliationView>(
      `/api/v1/admin/search/reconciliation?repair=${repair}`, auth)
  }

  return {
    shipments, dispatch, markDelivered, markFailed,
    returns, approveReturn, rejectReturn,
    products, createProduct, putOnShelf, takeOffShelf,
    activities, publishActivity, offlineActivity, warmUp,
    reindex, searchReconciliation,
  }
}
