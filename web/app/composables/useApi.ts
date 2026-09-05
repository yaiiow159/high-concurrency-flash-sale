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
 * 從一個 catch 到的東西取出可以顯示給使用者的訊息。
 *
 * <h2>為什麼不是就地寫 `(cause as { message?: string }).message`</h2>
 *
 * 那個寫法在專案裡出現過 30 次，而它有兩個問題：
 *
 * 1. **型別斷言是宣稱，不是檢查。** catch 到的不保證是 Error——
 *    網路層可能丟字串、丟 DOMException。此時 `.message` 是 undefined，
 *    畫面安靜地顯示 fallback，而真正的原因就消失了，
 *    連 console 都不會留下線索。
 * 2. **拿不到錯誤碼。** `ApiError` 帶著 code 與 retryable，
 *    而斷言成 `{ message }` 等於把它們丟掉——
 *    於是「可重試」與「業務錯誤」在 UI 上長得一模一樣。
 *
 * 這個函式只做取訊息這件事；要分流的地方仍然應該用
 * `error instanceof ApiError` 去看 code，那是有意義的分支。
 *
 * @param fallback 取不到訊息時顯示的字。**必填**——
 *                 沒有預設值是刻意的：一個泛用的「操作失敗」
 *                 對使用者毫無幫助，每個呼叫端都該說清楚是什麼失敗了
 */
export function errorMessage(cause: unknown, fallback: string): string {
  if (cause instanceof ApiError) {
    return cause.message || fallback
  }
  // 非 ApiError 的 Error（TypeError、AbortError…）也有 message，
  // 但它們是技術訊息，不適合直接給使用者看——記進 console 供排查，
  // 畫面顯示呼叫端寫的那句話
  if (cause instanceof Error) {
    console.error(fallback, cause)
    return fallback
  }
  console.error(fallback, cause)
  return fallback
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
