<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ActivityView } from '~/types/api'

/**
 * 秒殺活動。
 *
 * 這一頁的每個動作都比商品管理危險一階，因為它們牽動的是**庫存**：
 *
 * - 上架：立刻開放搶購
 * - 下架：立刻擋住新的搶購（已成立的訂單與已扣的庫存不受影響）
 * - 預熱：把庫存寫進 Redis
 *
 * 餘量是**當下的 Redis 值，不經快取**。維運剛下架一檔活動、
 * 後台卻因為快取還顯示上架中，他會再按一次——而那才是真正危險的地方。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { activities, publishActivity, offlineActivity, warmUp } = useAdmin()

const rows = ref<ActivityView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const message = ref<string | null>(null)
const busy = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await activities()
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入活動清單')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function toggle(activity: ActivityView) {
  const goingOnline = activity.status !== 'ONLINE'
  if (!goingOnline
    && !confirm(`下架「${activity.productName}」？\n\n新的搶購會立刻被擋下。`
      + `\n已成立的訂單與已扣的庫存不受影響。`)) {
    return
  }
  await run(activity.activityId, async () => {
    await (goingOnline
      ? publishActivity(activity.activityId)
      : offlineActivity(activity.activityId))
    message.value = `「${activity.productName}」已${goingOnline ? '上架' : '下架'}`
  })
}

/**
 * 預熱。
 *
 * **不提供 `force`。** 覆寫既有餘量是維運補救手段，
 * 誤按的代價是把賣掉的量重新放出來賣一次。要用它就用 curl——
 * 那道摩擦力本身就是保護措施（ADR-0015 決策 6 的延伸）。
 */
async function prewarm(activity: ActivityView) {
  if (!confirm(`預熱「${activity.productName}」的庫存？\n\n會把「總量 − 已售出」寫進 Redis。`)) {
    return
  }
  await run(activity.activityId, async () => {
    await warmUp(activity.activityId, false)
    message.value = `「${activity.productName}」預熱完成`
  })
}

async function run(activityId: number, action: () => Promise<void>) {
  busy.value = activityId
  error.value = null
  message.value = null
  try {
    await action()
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '操作失敗')
  } finally {
    busy.value = null
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-TW', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

onMounted(load)

useHead({ title: '秒殺活動' })
</script>

<template>
  <div>
    <AdminPageHeader title="秒殺活動" description="上下架與庫存預熱；餘量取自 Redis 當下的值">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <p
      v-if="error"
      class="mb-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="message"
      class="mb-4 rounded-sm border border-ok/40 bg-ok-soft p-3 text-sm"
      :style="{ color: 'var(--ok)' }"
      role="status"
    >
      {{ message }}
    </p>

    <div v-if="loading" class="flex flex-col gap-2">
      <SkeletonBlock class="h-24" />
      <SkeletonBlock class="h-24" />
    </div>

    <EmptyState v-else-if="rows.length === 0" title="還沒有任何秒殺活動。" />

    <ul v-else class="flex flex-col gap-2">
      <li v-for="activity in rows" :key="activity.activityId">
        <AppCard class="p-4">
          <div class="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="text-sm font-medium">{{ activity.productName }}</span>
                <StatusBadge :status="activity.status" />
              </div>
              <dl class="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink-muted">
                <div class="flex gap-1.5">
                  <dt class="text-ink-faint">開始</dt>
                  <dd class="figure">{{ formatTime(activity.startAt) }}</dd>
                </div>
                <div class="flex gap-1.5">
                  <dt class="text-ink-faint">結束</dt>
                  <dd class="figure">{{ formatTime(activity.endAt) }}</dd>
                </div>
                <div class="flex gap-1.5">
                  <dt class="text-ink-faint">秒殺價</dt>
                  <dd class="figure">NT$ {{ activity.seckillPrice.toLocaleString() }}</dd>
                </div>
              </dl>
            </div>

            <div class="flex shrink-0 items-center gap-4">
              <div class="text-right">
                <p class="eyebrow">Redis 餘量</p>
                <p class="figure text-2xl font-bold leading-none">
                  {{ activity.availableStock.toLocaleString() }}
                </p>
                <p class="mt-0.5 text-[11px] text-ink-faint">
                  共 <span class="figure">{{ activity.totalStock.toLocaleString() }}</span>
                </p>
              </div>

              <div class="flex flex-col gap-2">
                <AppButton
                  :variant="activity.status === 'ONLINE' ? 'secondary' : 'primary'"
                  size="sm"
                  :disabled="busy === activity.activityId"
                  @click="toggle(activity)"
                >
                  {{ activity.status === 'ONLINE' ? '下架' : '上架' }}
                </AppButton>
                <AppButton
                  variant="secondary" size="sm"
                  :disabled="busy === activity.activityId"
                  @click="prewarm(activity)"
                >
                  預熱庫存
                </AppButton>
              </div>
            </div>
          </div>
        </AppCard>
      </li>
    </ul>
  </div>
</template>
