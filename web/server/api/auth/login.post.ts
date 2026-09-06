import { backendUrl, setRefreshCookie, toClientSession } from '../../utils/backend'
import type { ApiResponse, SessionTokens } from '../../utils/backend'

/** 登入 BFF。 */
export default defineEventHandler(async (event) => {
  const body = await readBody(event)

  const response = await $fetch<ApiResponse<SessionTokens>>(backendUrl('/api/v1/auth/login'), {
    method: 'POST',
    body,
  })

  setRefreshCookie(event, response.data.refreshToken, response.data.refreshTokenExpiresInSeconds)
  return toClientSession(response.data)
})
