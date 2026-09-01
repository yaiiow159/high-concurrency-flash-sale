import { REFRESH_COOKIE, backendUrl, setRefreshCookie, toClientSession } from '../../utils/backend'
import type { ApiResponse, SessionTokens } from '../../utils/backend'

/**
 * 續期 BFF。
 *
 * <p>後端每次續期都會<b>輪替</b> refresh token，舊的立即失效。
 * 因此這裡必須把新的寫回 cookie——漏了這一步，
 * 下一次續期會拿著已失效的舊 token，反而觸發後端的重用偵測，
 * 導致整條輪替鏈被撤銷、使用者莫名被登出。
 */
export default defineEventHandler(async (event) => {
  const refreshToken = getCookie(event, REFRESH_COOKIE)
  if (!refreshToken) {
    throw createError({ statusCode: 401, statusMessage: '沒有登入憑證' })
  }

  const response = await $fetch<ApiResponse<SessionTokens>>(backendUrl('/api/v1/auth/refresh'), {
    method: 'POST',
    body: { refreshToken },
  })

  setRefreshCookie(event, response.data.refreshToken, response.data.refreshTokenExpiresInSeconds)
  return toClientSession(response.data)
})
