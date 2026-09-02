import { useAuthStore } from '~/stores/auth'
import type { ApiResponse } from '~/types/api'

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  /** 需要帶 access token 的請求 */
  authenticated?: boolean
}

/** 後端業務錯誤，帶著錯誤碼供 UI 分流。 */
export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
    readonly retryable: boolean,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

/**
 * 後端 API 的呼叫封裝。
 *
 * 集中處理三件事：帶令牌、401 時自動續期並重送、錯誤轉譯。
 * 散落在各元件裡的話，遲早會有某個呼叫忘了處理 401，
 * 表現成「偶爾要重新整理才能用」這種難查的問題。
 */
export function useApi() {
  const auth = useAuthStore()

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    try {
      return await send<T>(path, options, auth.accessToken)
    } catch (error) {
      // 只有帶令牌的請求才值得續期重試；匿名請求收到 401 是別的問題
      if (!(error instanceof ApiError) || error.status !== 401 || !options.authenticated) {
        throw error
      }
      const renewed = await auth.refresh()
      if (!renewed) {
        throw error
      }
      return await send<T>(path, options, renewed)
    }
  }

  async function send<T>(
    path: string,
    options: RequestOptions,
    token: string | null,
  ): Promise<T> {
    const headers: Record<string, string> = {}
    if (options.authenticated && token) {
      headers.Authorization = `Bearer ${token}`
    }
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }

    try {
      const response = await $fetch<ApiResponse<T>>(path, {
        method: options.method ?? 'GET',
        headers,
        body: options.body as Record<string, unknown> | undefined,
      })
      // 204 No Content 沒有回應主體，response 會是 undefined。
      // 直接讀 .data 會在刪除這類操作上炸掉，而那是完全成功的請求。
      return response?.data as T
    } catch (error) {
      throw toApiError(error)
    }
  }

  /**
   * 把 fetch 的錯誤轉成帶錯誤碼的 ApiError。
   *
   * 後端所有端點都回相同的 `ApiResponse` 結構（含 401/403），
   * 因此這裡永遠拿得到錯誤碼——UI 才能區分「已售罄」與「系統忙碌」
   * 而不必去比對訊息字串。
   */
  function toApiError(error: unknown): ApiError {
    const fetchError = error as { status?: number; data?: ApiResponse<unknown> }
    const status = fetchError.status ?? 0
    const body = fetchError.data

    if (body?.code) {
      return new ApiError(body.code, body.message, status, body.retryable)
    }
    return new ApiError('NETWORK', '網路連線異常，請稍後再試', status, true)
  }

  return { request }
}
