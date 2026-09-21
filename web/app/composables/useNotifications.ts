import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { NotificationView } from '~/types/api'

/** 站內信。 <b>未讀數與列表分開取</b>：導覽列的紅點在每一頁都需要它， 而那些頁面不會順便載入整份通知列表。把兩者綁在一起， 等於每次要更新紅點就得抓 20 筆通知回來。 */
/** 未讀數全站共用：通知頁標記已讀之後，頁首的紅點要跟著變，而那是另一個元件。 */
const unreadCount = ref(0)

/** 紅點的更新間隔。通知不是即時訊息，一分鐘內看到就夠了。 */
const UNREAD_POLL_INTERVAL_MILLIS = 60_000

export function useNotifications() {
  const { request } = useApi()
  const auth = useAuthStore()

  const notifications = ref<NotificationView[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      notifications.value = await request<NotificationView[]>('/api/v1/notifications', {
        authenticated: true,
      })
    } catch (cause) {
      error.value = errorMessage(cause, '無法載入通知')
    } finally {
      loading.value = false
    }
  }

  /** 只取未讀數。 失敗時<b>安靜地維持原值</b>而不是清成 0——導覽列上的紅點消失， 使用者會以為通知都讀完了，而那是我們自己請求失敗造成的錯覺。 */
  async function refreshUnreadCount(): Promise<void> {
    // 沒登入就不問：定期刷新會讓每個訪客每分鐘吃一次 401 再白跑一次續期
    if (!auth.isAuthenticated) {
      unreadCount.value = 0
      return
    }
    try {
      const result = await request<{ count: number }>('/api/v1/notifications/unread-count', {
        authenticated: true,
      })
      unreadCount.value = result.count
    } catch {
      // 維持原值
    }
  }

  async function markRead(notificationId: number): Promise<void> {
    await request<NotificationView>(`/api/v1/notifications/${notificationId}/read`, {
      method: 'POST', authenticated: true,
    })
    await Promise.all([load(), refreshUnreadCount()])
  }

  async function markAllRead(): Promise<void> {
    await request<{ marked: number }>('/api/v1/notifications/read-all', {
      method: 'POST', authenticated: true,
    })
    await Promise.all([load(), refreshUnreadCount()])
  }

  /**
   * 分頁可見時才定期刷新紅點。付款成功、出貨都會產生通知，
   * 只在進站時抓一次的話，使用者要換頁才知道有新消息。
   */
  function watchUnreadCount(): void {
    let timer: ReturnType<typeof setInterval> | null = null

    const start = () => {
      if (timer === null) {
        timer = setInterval(() => { void refreshUnreadCount() }, UNREAD_POLL_INTERVAL_MILLIS)
      }
    }
    const stop = () => {
      if (timer !== null) {
        clearInterval(timer)
        timer = null
      }
    }
    const onVisibilityChange = () => {
      if (document.hidden) {
        stop()
        return
      }
      void refreshUnreadCount()
      start()
    }

    onMounted(() => {
      document.addEventListener('visibilitychange', onVisibilityChange)
      if (!document.hidden) {
        start()
      }
    })
    onUnmounted(() => {
      document.removeEventListener('visibilitychange', onVisibilityChange)
      stop()
    })
  }

  return {
    watchUnreadCount,
    notifications: readonly(notifications),
    unreadCount: readonly(unreadCount),
    loading: readonly(loading),
    error: readonly(error),
    load,
    refreshUnreadCount,
    markRead,
    markAllRead,
  }
}
