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
    setSession,
    clear,
    refresh,
    login,
    register,
    logout,
  }
})
