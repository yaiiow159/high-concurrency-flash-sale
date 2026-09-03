<script setup lang="ts">
import type { DeepReadonly } from 'vue'
import { useNotifications } from '~/composables/useNotifications'
import { useAuthStore } from '~/stores/auth'
import type { NotificationView } from '~/types/api'

/**
 * 通知中心。
 *
 * <p><b>不做 SSR、不做 ISR</b>——通知是每個人專屬的資料，
 * 進了被快取的 HTML 就等於發給下一個訪客。
 *
 * <p>文字完全來自後端的快照，這一頁只負責排版。在前端組裝訊息文字
 * 等於讓「我們對使用者說過什麼」有第二個版本，
 * 而客訴時只有後端那份算數。
 */
type ReadonlyNotification = DeepReadonly<NotificationView>

const auth = useAuthStore()
const { notifications, unreadCount, loading, error, load, refreshUnreadCount, markRead, markAllRead }
  = useNotifications()

const working = ref(false)

/**
 * 點通知就標記已讀並跳到關聯的單據。
 *
 * 已讀與導頁一起做，而不是要求使用者另外按一個「標為已讀」——
 * 多一個按鈕只會讓紅點永遠清不掉。
 */
async function open(notification: ReadonlyNotification) {
  if (notification.unread) {
    await markRead(notification.notificationId).catch(() => undefined)
  }
  const target = destinationOf(notification)
  if (target) {
    await navigateTo(target)
  }
}

/**
 * 通知該連去哪裡。
 *
 * 由類型決定而不是把網址存進通知內容：網址在不同環境不一樣，
 * 寫進快照的話正式環境會出現指向 localhost 的連結。
 */
function destinationOf(notification: ReadonlyNotification): string | null {
  if (!notification.referenceNo) {
    return null
  }
  return notification.type === 'REFUND_SENT'
    ? `/returns/${notification.referenceNo}`
    : `/orders/${notification.referenceNo}`
}

async function readAll() {
  working.value = true
  try {
    await markAllRead()
  } finally {
    working.value = false
  }
}

function formatDate(value: string): string {
  return new Date(value).toLocaleString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

onMounted(() => {
  if (auth.isAuthenticated) {
    load()
    refreshUnreadCount()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    load()
    refreshUnreadCount()
  }
})

useHead({ title: '通知' })
</script>

<template>
  <div>
    <PageHeader eyebrow="Notifications" title="通知">
      <template #actions>
        <AppButton
          v-if="unreadCount > 0"
          variant="ghost"
          size="sm"
          :disabled="working"
          @click="readAll"
        >
          全部標為已讀
        </AppButton>
      </template>
    </PageHeader>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <div v-if="loading" class="flex flex-col gap-3">
        <SkeletonCard v-for="n in 4" :key="n" variant="row" />
      </div>

      <p
        v-else-if="error"
        class="rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>

      <ul v-else-if="notifications.length > 0" class="flex flex-col gap-2">
        <li v-for="notification in notifications" :key="notification.notificationId">
          <AppCard
            interactive
            class="flex items-start gap-3 p-4"
            :class="notification.unread ? 'border-cta/40' : ''"
            role="button"
            tabindex="0"
            @click="open(notification)"
            @keydown.enter="open(notification)"
          >
            <!-- 未讀用一個小圓點而不是整張卡片變色：整張變色在列表裡
                 會讓已讀的那些看起來像是停用了 -->
            <span
              class="mt-1.5 h-2 w-2 shrink-0 rounded-full"
              :class="notification.unread ? 'bg-cta' : 'bg-transparent'"
              :aria-label="notification.unread ? '未讀' : undefined"
            />
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-baseline justify-between gap-x-3">
                <p :class="notification.unread ? 'font-semibold' : 'font-medium text-ink-muted'">
                  {{ notification.title }}
                </p>
                <span class="figure text-xs text-ink-faint">
                  {{ formatDate(notification.createdAt) }}
                </span>
              </div>
              <p class="mt-1 text-sm text-ink-muted">{{ notification.body }}</p>
            </div>
          </AppCard>
        </li>
      </ul>

      <EmptyState
        v-else
        title="還沒有任何通知。"
        hint="訂單付款、出貨、送達與退款都會通知你。"
      >
        <AppButton variant="secondary" size="sm" @click="navigateTo('/orders')">
          去看我的訂單
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
