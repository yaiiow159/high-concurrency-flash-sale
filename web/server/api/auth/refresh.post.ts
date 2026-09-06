import { REFRESH_COOKIE, backendUrl, setRefreshCookie, toClientSession } from '../../utils/backend'
import type { ApiResponse, SessionTokens } from '../../utils/backend'

/** 續期 BFF。 */
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
