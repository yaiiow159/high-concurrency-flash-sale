import { useApi } from '~/composables/useApi'
import type {
  ExchangeResult,
  ExchangeableCouponView,
  MemberProfileView,
  PointTransactionView,
} from '~/types/api'

/**
 * 會員中心的 API。
 *
 * 每一支都不帶 userId——身分來自令牌。積分是資產，
 * 讓呼叫端指定要看誰的餘額等於讓它看別人的錢包。
 */
export function useMembership() {
  const { request } = useApi()
  const auth = { authenticated: true } as const

  function profile() {
    return request<MemberProfileView>('/api/v1/membership/profile', auth)
  }

  function points(page = 0, size = 20) {
    return request<PointTransactionView[]>(
      `/api/v1/membership/points?page=${page}&size=${size}`, auth)
  }

  function exchangeable() {
    return request<ExchangeableCouponView[]>('/api/v1/membership/exchange', auth)
  }

  function exchange(promotionId: number) {
    return request<ExchangeResult>(`/api/v1/membership/exchange/${promotionId}`,
      { ...auth, method: 'POST' })
  }

  return { profile, points, exchangeable, exchange }
}
