import { useApi } from '~/composables/useApi'
import type { NotificationView } from '~/types/api'

/**
 * 站內信。
 *
 * <b>未讀數與列表分開取</b>：導覽列的紅點在每一頁都需要它，
 * 而那些頁面不會順便載入整份通知列表。把兩者綁在一起，
 * 等於每次要更新紅點就得抓 20 筆通知回來。
 */
export function useNotifications() {
  const { request } = useApi()

  const notifications = ref<NotificationView[]>([])
  const unreadCount = ref(0)
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
      error.value = (cause as { message?: string }).message ?? '無法載入通知'
    } finally {
      loading.value = false
    }
  }

  /**
   * 只取未讀數。
   *
   * 失敗時<b>安靜地維持原值</b>而不是清成 0——導覽列上的紅點消失，
   * 使用者會以為通知都讀完了，而那是我們自己請求失敗造成的錯覺。
   */
  async function refreshUnreadCount(): Promise<void> {
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

  return {
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
