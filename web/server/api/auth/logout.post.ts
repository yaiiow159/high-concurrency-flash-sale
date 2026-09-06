import { REFRESH_COOKIE, backendUrl, clearRefreshCookie } from '../../utils/backend'

/** 登出 BFF。 */
export default defineEventHandler(async (event) => {
  const refreshToken = getCookie(event, REFRESH_COOKIE)
  clearRefreshCookie(event)

  if (refreshToken) {
    await $fetch(backendUrl('/api/v1/auth/logout'), {
      method: 'POST',
      body: { refreshToken },
    }).catch(() => {
      // 後端撤銷失敗時，cookie 已清除，令牌會在 7 天後自然過期
    })
  }
  return { ok: true }
})
