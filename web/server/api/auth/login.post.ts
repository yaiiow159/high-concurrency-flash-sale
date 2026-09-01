import { backendUrl, setRefreshCookie, toClientSession } from '../../utils/backend'
import type { ApiResponse, SessionTokens } from '../../utils/backend'

/**
 * 登入 BFF。
 *
 * <p>後端在回應主體中同時給出 access 與 refresh token。
 * 這一層把 refresh token 攔下來寫進 httpOnly cookie，
 * <b>只把 access token 交給瀏覽器</b>——refresh token 從頭到尾不進 JS 環境。
 */
export default defineEventHandler(async (event) => {
  const body = await readBody(event)

  const response = await $fetch<ApiResponse<SessionTokens>>(backendUrl('/api/v1/auth/login'), {
    method: 'POST',
    body,
  })

  setRefreshCookie(event, response.data.refreshToken, response.data.refreshTokenExpiresInSeconds)
  return toClientSession(response.data)
})
