import { useApi } from '~/composables/useApi'
import type {
  ProductRatingView,
  ReviewView,
  ReviewableView,
  WriteReviewPayload,
} from '~/types/api'

/**
 * 評價的讀寫。
 *
 * 讀取那兩支是**公開**的（`authenticated: false`），這不只是設定：
 * 評價存在的意義就是幫「還沒買、也還沒登入」的人做決定。
 * 帶上 Authorization 反而會讓這些回應無法被共用快取。
 */
export function useReviews() {
  const { request } = useApi()

  const rating = ref<ProductRatingView | null>(null)
  const reviews = ref<ReviewView[]>([])
  const loading = ref(false)
  /** 還有下一頁。用「這一頁滿了」推斷，不另外要一個 total——那要多一次 COUNT。 */
  const hasMore = ref(false)

  const PAGE_SIZE = 10
  let page = 0

  /**
   * 載入評分摘要與第一頁評價。
   *
   * 兩支併發送出而不是接力：它們互不依賴，串起來只是把延遲加倍。
   *
   * **失敗時不擋住商品頁。** 評價掛掉時使用者仍然應該買得到東西——
   * 這是 fail-open，因為這道防線失守的代價只是「少看到評價」。
   * 與庫存的 fail-closed 剛好相反，判準是「失守會付出什麼代價」。
   */
  async function load(productId: number | string) {
    loading.value = true
    page = 0
    try {
      const [summary, firstPage] = await Promise.all([
        request<ProductRatingView>(`/api/v1/catalog/products/${productId}/rating`),
        request<ReviewView[]>(
          `/api/v1/catalog/products/${productId}/reviews?page=0&size=${PAGE_SIZE}`),
      ])
      rating.value = summary
      reviews.value = firstPage
      hasMore.value = firstPage.length === PAGE_SIZE
    } catch {
      rating.value = null
      reviews.value = []
      hasMore.value = false
    } finally {
      loading.value = false
    }
  }

  /** 載入下一頁並附加。失敗時保留已載入的內容，不清空。 */
  async function loadMore(productId: number | string) {
    if (loading.value || !hasMore.value) {
      return
    }
    loading.value = true
    try {
      const next = await request<ReviewView[]>(
        `/api/v1/catalog/products/${productId}/reviews?page=${page + 1}&size=${PAGE_SIZE}`)
      reviews.value = [...reviews.value, ...next]
      hasMore.value = next.length === PAGE_SIZE
      page += 1
    } catch {
      hasMore.value = false
    } finally {
      loading.value = false
    }
  }

  /** 這張訂單現在能評什麼。可評項目由後端算，前端不自己比對。 */
  function reviewable(orderNo: string) {
    return request<ReviewableView>(`/api/v1/orders/${orderNo}/reviewable`,
      { authenticated: true })
  }

  function write(orderNo: string, payload: WriteReviewPayload) {
    return request<ReviewView>(`/api/v1/orders/${orderNo}/reviews`, {
      method: 'POST',
      authenticated: true,
      body: payload,
    })
  }

  function edit(reviewId: number, payload: WriteReviewPayload) {
    return request<ReviewView>(`/api/v1/reviews/${reviewId}`, {
      method: 'PUT',
      authenticated: true,
      body: payload,
    })
  }

  function mine(page = 0, size = 20) {
    return request<ReviewView[]>(`/api/v1/reviews/mine?page=${page}&size=${size}`,
      { authenticated: true })
  }

  return { rating, reviews, loading, hasMore, load, loadMore, reviewable, write, edit, mine }
}
