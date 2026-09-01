import type { H3Event } from 'h3'

/**
 * Refresh token 的 cookie 名稱。
 *
 * 存在 httpOnly cookie 而非 localStorage：後者是 XSS 的直接目標，
 * 任何被注入的第三方腳本都能讀走它。httpOnly 讓 JS 完全碰不到。
 */
export const REFRESH_COOKIE = 'fs_rt'

export interface SessionTokens {
  accessToken: string
  refreshToken: string
  tokenType: string
  accessTokenExpiresInSeconds: number
  refreshTokenExpiresInSeconds: number
}

export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  retryable: boolean
}

export function backendUrl(path: string): string {
  return `${useRuntimeConfig().apiBase}${path}`
}

/**
 * 寫入 refresh token cookie。
 *
 * sameSite 用 'lax' 而非 'strict'：strict 會讓使用者從外部連結進站時
 * 帶不上 cookie，看起來像被登出。lax 已經擋掉 CSRF 的主要向量（跨站 POST），
 * 而本站的寫入操作又都需要 Authorization 標頭——那是 cookie 帶不上的東西。
 */
export function setRefreshCookie(event: H3Event, token: string, maxAgeSeconds: number): void {
  setCookie(event, REFRESH_COOKIE, token, {
    httpOnly: true,
    sameSite: 'lax',
    // 本機開發走 http，正式環境必須為 true
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: maxAgeSeconds,
  })
}

export function clearRefreshCookie(event: H3Event): void {
  deleteCookie(event, REFRESH_COOKIE, { path: '/' })
}

/**
 * 只回傳 access token 與其效期給瀏覽器。
 *
 * refresh token 刻意不在回應中——它只存在於 httpOnly cookie 裡。
 */
export function toClientSession(tokens: SessionTokens) {
  return {
    accessToken: tokens.accessToken,
    tokenType: tokens.tokenType,
    expiresInSeconds: tokens.accessTokenExpiresInSeconds,
  }
}
