import { useApi } from '~/composables/useApi'
import type {
  OpenReturnPayload,
  ReturnRequestView,
  ReturnableView,
} from '~/types/api'

/**
 * 退貨退款。
 *
 * <b>可退數量一律問後端</b>，不在這裡自己扣。
 * 「審核中的退貨單也佔用額度」是領域規則，
 * 在前端複製一份的話兩邊遲早分岔，而分岔的症狀是
 * 「畫面說可以退，送出卻被拒絕」——那是最難debug的一種前端 bug，
 * 因為畫面看起來完全正常。
 */
export function useReturns() {
  const { request } = useApi()

  const returns = ref<ReturnRequestView[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      returns.value = await request<ReturnRequestView[]>('/api/v1/returns', {
        authenticated: true,
      })
    } catch (cause) {
      error.value = (cause as { message?: string }).message ?? '無法載入退貨紀錄'
    } finally {
      loading.value = false
    }
  }

  function inspect(orderNo: string): Promise<ReturnableView> {
    return request<ReturnableView>(`/api/v1/orders/${orderNo}/returnable`, {
      authenticated: true,
    })
  }

  function findOne(returnNo: string): Promise<ReturnRequestView> {
    return request<ReturnRequestView>(`/api/v1/returns/${returnNo}`, {
      authenticated: true,
    })
  }

  function open(orderNo: string, payload: OpenReturnPayload): Promise<ReturnRequestView> {
    return request<ReturnRequestView>(`/api/v1/orders/${orderNo}/returns`, {
      method: 'POST', authenticated: true, body: payload,
    })
  }

  /**
   * 撤回申請。
   *
   * 成功後重新載入整份清單而不是就地改狀態——與地址簿同樣的理由：
   * 在前端推算後端會怎麼變，等於把不變式複製一份過來。
   */
  async function cancel(returnNo: string): Promise<void> {
    await request<ReturnRequestView>(`/api/v1/returns/${returnNo}/cancel`, {
      method: 'POST', authenticated: true,
    })
    await load()
  }

  return {
    returns: readonly(returns),
    loading: readonly(loading),
    error: readonly(error),
    load,
    inspect,
    findOne,
    open,
    cancel,
  }
}
