import { REFRESH_COOKIE, backendUrl, clearRefreshCookie } from '../../utils/backend'

/**
 * 登出 BFF。
 *
 * <p>無論後端撤銷成功與否都清掉 cookie——使用者按了登出就該登出，
 * 不能因為後端暫時不可用就把人留在登入狀態。
 */
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
