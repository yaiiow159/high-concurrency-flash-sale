import { defineStore } from 'pinia'

interface ClientSession {
  accessToken: string
  tokenType: string
  expiresInSeconds: number
}

/**
 * 認證狀態。
 *
 * **access token 只放記憶體，刻意不持久化。**
 * 放進 localStorage 是 XSS 的直接目標——任何被注入的第三方腳本都能讀走它。
 * 放在記憶體裡，分頁一關就沒了，而重新整理後靠 httpOnly cookie 裡的
 * refresh token 自動續期即可，使用者無感。
 *
 * refresh token 完全不在這裡，它只存在於 BFF 設下的 httpOnly cookie 中。
 */
export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const userEmail = ref<string | null>(null)

  const isAuthenticated = computed(() => accessToken.value !== null)

  /**
   * 這個人有沒有 admin 權限。
   *
   * **只用來決定畫面上顯示什麼，永遠不是安全判斷。**
   * 令牌是使用者手上的字串，他要改成什麼都行——但改完之後
   * 打到後端仍然會被 `hasAuthority(SCOPE_ADMIN)` 擋下，
   * 因為那一邊是驗過簽章的（ADR-0015 決策 2）。
   *
   * 讀 payload 而不是另外要一支 `/me`：scope 本來就在令牌裡，
   * 多打一次 API 只是為了拿一個已經在手上的值。
   */
  const isAdmin = computed(() => readScopes(accessToken.value).includes('seckill:admin'))

  /**
   * 解出 JWT 的 scope claim。
   *
   * <p>只做 base64url 解碼，**不驗簽也不該驗簽**——驗簽需要公鑰，
   * 而前端就算驗過也證明不了什麼（要偽造的人可以連驗簽的程式碼一起改）。
   * 這裡要的只是「這個令牌自稱有什麼權限」，用來決定選單長什麼樣。
   *
   * <p>任何解析失敗都回空陣列：壞掉的令牌等於沒有權限，
   * 而不是讓整個 store 拋例外把頁面炸掉。
   */
  function readScopes(token: string | null): string[] {
    if (!token) {
      return []
    }
    try {
      const payload = token.split('.')[1]
      if (!payload) {
        return []
      }
      // JWT 用的是 base64url：- 與 _ 要換回 + 與 /，atob 才吃得下
      const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
      const claims = JSON.parse(json) as { scope?: unknown }
      return typeof claims.scope === 'string' ? claims.scope.split(' ') : []
    } catch {
      return []
    }
  }

  /** 同時只允許一次續期；見 useApi 對併發收斂的說明。 */
  let refreshInFlight: Promise<string | null> | null = null

  function setSession(session: ClientSession): void {
    accessToken.value = session.accessToken
  }

  function clear(): void {
    accessToken.value = null
    userEmail.value = null
  }

  /**
   * 續期，並確保併發呼叫只會實際發出一次請求。
   *
   * 這一點是必要的而非最佳化：後端每次續期都會**輪替** refresh token，
   * 舊的立即失效。若三個請求同時 401 而各自發一次續期，
   * 後兩次會拿著已失效的 token，反而觸發後端的重用偵測，
   * 導致整條輪替鏈被撤銷、使用者莫名被登出。
   *
   * 與後端的 Redis 冪等是同一類問題：**併發下的重複請求要收斂成一次**。
   */
  async function refresh(): Promise<string | null> {
    if (refreshInFlight) {
      return refreshInFlight
    }

    refreshInFlight = (async () => {
      try {
        const session = await $fetch<ClientSession>('/api/auth/refresh', { method: 'POST' })
        setSession(session)
        return session.accessToken
      } catch {
        clear()
        return null
      } finally {
        refreshInFlight = null
      }
    })()

    return refreshInFlight
  }

  async function login(email: string, password: string): Promise<void> {
    const session = await $fetch<ClientSession>('/api/auth/login', {
      method: 'POST',
      body: { email, password },
    })
    setSession(session)
    userEmail.value = email
  }

  async function register(email: string, password: string, displayName: string): Promise<void> {
    await $fetch('/api/auth/register', {
      method: 'POST',
      body: { email, password, displayName },
    })
    await login(email, password)
  }

  async function logout(): Promise<void> {
    await $fetch('/api/auth/logout', { method: 'POST' }).catch(() => undefined)
    clear()
  }

  return {
    accessToken: readonly(accessToken),
    userEmail: readonly(userEmail),
    isAuthenticated,
    isAdmin,
    setSession,
    clear,
    refresh,
    login,
    register,
    logout,
  }
})
