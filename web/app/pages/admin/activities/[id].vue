<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ActivityMonitorView } from '~/types/api'

/**
 * 秒殺活動即時監控。每兩秒取一次快照，速率由前端從累計值的差分算出——
 * 後端只給計數器，「每秒幾次」是這一頁自己的責任，這樣後端不必替每個看板維護視窗。
 *
 * 餘量與售罄標記直讀 Redis；計數器是**本節點**的指標暫存值，多副本時請看 Grafana。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const route = useRoute()
const activityId = Number(route.params.id)
const { activityMonitor } = useAdmin()

const POLL_MS = 2000
const HISTORY = 60

const snapshot = ref<ActivityMonitorView | null>(null)
const error = ref<string | null>(null)
const paused = ref(false)

/** 上一次快照的累計值，用來算每秒速率。 */
let previous: { at: number, attempts: number, success: number, rejected: number } | null = null

interface Rate { attempts: number, success: number, rejected: number }
const rate = ref<Rate>({ attempts: 0, success: 0, rejected: 0 })
const history = ref<number[]>([])

function sum(map: Record<string, number> | undefined): number {
  return Object.values(map ?? {}).reduce((total, value) => total + value, 0)
}

function attemptsOf(view: ActivityMonitorView): { total: number, success: number, rejected: number } {
  const byResult = view.counters.attemptsByResult
  const success = byResult.success ?? 0
  const rejected = byResult.rejected ?? 0
  const errorCount = byResult.error ?? 0
  return { total: success + rejected + errorCount, success, rejected }
}

async function tick() {
  if (paused.value) {
    return
  }
  try {
    const view = await activityMonitor(activityId)
    const now = Date.now()
    const current = attemptsOf(view)
    if (previous) {
      // 差分除以真實間隔，不是除以 POLL_MS：setInterval 會漂，分頁切到背景時會停
      const seconds = Math.max((now - previous.at) / 1000, 0.001)
      rate.value = {
        attempts: Math.max(0, (current.total - previous.attempts) / seconds),
        success: Math.max(0, (current.success - previous.success) / seconds),
        rejected: Math.max(0, (current.rejected - previous.rejected) / seconds),
      }
      history.value = [...history.value, rate.value.attempts].slice(-HISTORY)
    }
    previous = { at: now, attempts: current.total, success: current.success, rejected: current.rejected }
    snapshot.value = view
    error.value = null
  } catch (cause) {
    error.value = errorMessage(cause, '無法取得監控資料')
  }
}

let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void tick()
  timer = setInterval(tick, POLL_MS)
})
onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
  }
})

const activity = computed(() => snapshot.value?.activity ?? null)
const counters = computed(() => snapshot.value?.counters ?? null)

const soldRatio = computed(() => {
  if (!activity.value || activity.value.totalStock === 0) {
    return 0
  }
  return Math.min(1, 1 - activity.value.availableStock / activity.value.totalStock)
})

/** 拒絕原因的錯誤碼 → 人看得懂的字。沒對到的照碼顯示，不要藏。 */
const REJECTION_LABELS: Record<string, string> = {
  B0002: '活動未開始',
  B0003: '活動已結束',
  B0004: '活動未上架',
  B0005: '售罄',
  B0006: '超過限購',
  B0054: '未領資格',
  B0055: '資格無效',
  B0057: '黑名單',
  A0002: '限流',
  C0001: '庫存服務不可用',
}

function label(code: string): string {
  return REJECTION_LABELS[code] ?? code
}

/** 60 個取樣點的折線，最大值貼頂。 */
const sparkline = computed(() => {
  const points = history.value
  if (points.length < 2) {
    return ''
  }
  const max = Math.max(...points, 1)
  const width = 240
  const height = 48
  const step = width / (HISTORY - 1)
  return points
    .map((value, index) => `${(index + (HISTORY - points.length)) * step},${height - (value / max) * (height - 4) - 2}`)
    .join(' ')
})

function formatTime(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleTimeString('zh-TW') : '—'
}

function fixed(value: number, digits = 1): string {
  return value.toLocaleString('zh-TW', { maximumFractionDigits: digits, minimumFractionDigits: 0 })
}

useHead({ title: computed(() => activity.value ? `監控 · ${activity.value.productName}` : '活動監控') })
</script>

<template>
  <div>
    <AdminPageHeader
      :title="activity ? activity.productName : '活動監控'"
      :description="snapshot ? `每 2 秒更新 · 上次取樣 ${formatTime(snapshot.sampledAt)} · 計數為本節點值` : '連線中⋯'"
    >
      <template #actions>
        <NuxtLink to="/admin/activities" class="text-sm text-ink-muted hover:text-accent">← 活動列表</NuxtLink>
        <AppButton variant="secondary" size="sm" @click="paused = !paused">
          {{ paused ? '繼續更新' : '暫停更新' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <p v-if="error" class="mb-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger" role="alert">
      {{ error }}
    </p>

    <div v-if="!snapshot" class="grid gap-3 md:grid-cols-3">
      <SkeletonBlock v-for="n in 6" :key="n" class="h-28" />
    </div>

    <template v-else>
      <!-- 第一列：庫存、售罄標記、佇列。這三格回答「還能不能賣、賣得動不動」 -->
      <div class="grid gap-3 md:grid-cols-3">
        <AppCard class="p-4">
          <p class="eyebrow">Redis 餘量</p>
          <p class="figure mt-1 text-3xl font-bold leading-none">
            {{ activity!.availableStock.toLocaleString() }}
            <span class="text-sm font-normal text-ink-faint">/ {{ activity!.totalStock.toLocaleString() }}</span>
          </p>
          <div class="mt-3 h-2 overflow-hidden rounded-full bg-sunken" role="progressbar" :aria-valuenow="Math.round(soldRatio * 100)" aria-valuemin="0" aria-valuemax="100">
            <div class="h-full bg-accent transition-all duration-500" :style="{ width: `${soldRatio * 100}%` }" />
          </div>
          <p class="mt-1 text-[11px] text-ink-faint">已售出 {{ Math.round(soldRatio * 100) }}%</p>
        </AppCard>

        <AppCard class="p-4" :highlighted="snapshot.soldOutMarked">
          <p class="eyebrow">本機售罄標記</p>
          <p class="mt-1 text-2xl font-bold leading-none" :class="snapshot.soldOutMarked ? 'text-danger' : 'text-ok'">
            {{ snapshot.soldOutMarked ? '已豎起' : '未豎起' }}
          </p>
          <p class="mt-2 text-[11px] text-ink-faint">
            豎起後洪峰在本機就被擋下，不再打到 Redis（第一層漏斗）
          </p>
          <div class="mt-2 flex items-center gap-2">
            <StatusBadge :status="activity!.status" />
            <span class="text-xs text-ink-muted">{{ activity!.purchasable ? '可搶購' : '不可搶購' }}</span>
          </div>
        </AppCard>

        <AppCard class="p-4" :highlighted="snapshot.queueBacklog > 0">
          <p class="eyebrow">建單佇列</p>
          <p class="figure mt-1 text-3xl font-bold leading-none">
            {{ snapshot.queueBacklog.toLocaleString() }}
            <span class="text-sm font-normal text-ink-faint">筆積壓</span>
          </p>
          <dl class="mt-2 grid grid-cols-2 gap-x-3 text-xs text-ink-muted">
            <dt>消化速率</dt>
            <dd class="figure text-right text-ink">{{ fixed(snapshot.queueDrainRatePerSecond) }} /s</dd>
            <dt>預估等待</dt>
            <dd class="figure text-right text-ink">{{ snapshot.queueEstimatedWaitSeconds }} 秒</dd>
          </dl>
        </AppCard>
      </div>

      <!-- 第二列：流量。每秒請求數是這一頁的主角，其餘是它的分解 -->
      <div class="mt-3 grid gap-3 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <AppCard class="p-4">
          <div class="flex items-baseline justify-between">
            <p class="eyebrow">每秒請求</p>
            <span class="text-[11px] text-ink-faint">最近 {{ HISTORY * POLL_MS / 1000 }} 秒</span>
          </div>
          <p class="figure mt-1 text-4xl font-bold leading-none">{{ fixed(rate.attempts) }}</p>
          <svg viewBox="0 0 240 48" class="mt-3 h-12 w-full text-accent" aria-hidden="true" preserveAspectRatio="none">
            <polyline :points="sparkline" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
          </svg>
          <dl class="mt-2 grid grid-cols-2 gap-x-3 text-xs text-ink-muted">
            <dt>成功 /s</dt>
            <dd class="figure text-right text-ok">{{ fixed(rate.success) }}</dd>
            <dt>拒絕 /s</dt>
            <dd class="figure text-right text-ink">{{ fixed(rate.rejected) }}</dd>
          </dl>
        </AppCard>

        <AppCard class="p-4">
          <p class="eyebrow">累計</p>
          <dl class="mt-2 grid grid-cols-3 gap-3">
            <div>
              <dt class="text-xs text-ink-muted">成功</dt>
              <dd class="figure text-2xl font-bold text-ok">{{ (counters!.attemptsByResult.success ?? 0).toLocaleString() }}</dd>
            </div>
            <div>
              <dt class="text-xs text-ink-muted">拒絕</dt>
              <dd class="figure text-2xl font-bold">{{ (counters!.attemptsByResult.rejected ?? 0).toLocaleString() }}</dd>
            </div>
            <div>
              <dt class="text-xs text-ink-muted">錯誤</dt>
              <dd class="figure text-2xl font-bold" :class="(counters!.attemptsByResult.error ?? 0) > 0 ? 'text-danger' : ''">
                {{ (counters!.attemptsByResult.error ?? 0).toLocaleString() }}
              </dd>
            </div>
          </dl>
          <dl class="mt-4 grid grid-cols-2 gap-x-3 text-xs text-ink-muted">
            <dt>端到端 p95（近 2 分鐘）</dt>
            <dd class="figure text-right text-ink">{{ fixed(counters!.attemptP95Millis) }} ms</dd>
            <dt>端到端 p99（近 2 分鐘）</dt>
            <dd class="figure text-right text-ink">{{ fixed(counters!.attemptP99Millis) }} ms</dd>
          </dl>
        </AppCard>
      </div>

      <!-- 第三列：拒絕原因、投遞、落庫。這三格回答「被擋的是誰、送出去了沒、單建成了沒」 -->
      <div class="mt-3 grid gap-3 md:grid-cols-3">
        <AppCard class="p-4">
          <p class="eyebrow">拒絕原因</p>
          <ul v-if="sum(counters!.rejectionsByCode) > 0" class="mt-2 flex flex-col gap-1.5">
            <li v-for="(count, code) in counters!.rejectionsByCode" :key="code" class="text-xs">
              <div class="flex justify-between">
                <span>{{ label(String(code)) }} <span class="figure text-ink-faint">{{ code }}</span></span>
                <span class="figure">{{ count.toLocaleString() }}</span>
              </div>
              <div class="mt-0.5 h-1 overflow-hidden rounded-full bg-sunken">
                <div class="h-full bg-ink-muted" :style="{ width: `${(count / sum(counters!.rejectionsByCode)) * 100}%` }" />
              </div>
            </li>
          </ul>
          <p v-else class="mt-2 text-xs text-ink-faint">還沒有任何拒絕</p>
        </AppCard>

        <AppCard class="p-4" :highlighted="(counters!.publishByOutcome.pending ?? 0) > 0">
          <p class="eyebrow">建單訊息投遞</p>
          <dl class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs text-ink-muted">
            <dt>已確認</dt>
            <dd class="figure text-right text-ok">{{ (counters!.publishByOutcome.acked ?? 0).toLocaleString() }}</dd>
            <dt>等待中</dt>
            <dd class="figure text-right" :class="(counters!.publishByOutcome.pending ?? 0) > 0 ? 'text-accent' : 'text-ink'">
              {{ (counters!.publishByOutcome.pending ?? 0).toLocaleString() }}
            </dd>
            <dt>失敗（已退庫）</dt>
            <dd class="figure text-right" :class="(counters!.publishByOutcome.failed ?? 0) > 0 ? 'text-danger' : 'text-ink'">
              {{ (counters!.publishByOutcome.failed ?? 0).toLocaleString() }}
            </dd>
            <dt>補償退庫</dt>
            <dd class="figure text-right text-ink">{{ sum(counters!.compensationsByResult).toLocaleString() }}</dd>
          </dl>
          <p class="mt-2 text-[11px] text-ink-faint">
            等待中 = 逾時但生產者仍在重試，不是失敗；持續偏高代表 broker 變慢（ADR-0030）
          </p>
        </AppCard>

        <AppCard class="p-4">
          <p class="eyebrow">消費端落庫</p>
          <dl v-if="sum(counters!.persistedByResult) > 0" class="mt-2 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs text-ink-muted">
            <template v-for="(count, result) in counters!.persistedByResult" :key="result">
              <dt>{{ result }}</dt>
              <dd class="figure text-right text-ink">{{ count.toLocaleString() }}</dd>
            </template>
          </dl>
          <p v-else class="mt-2 text-xs text-ink-faint">這個節點還沒消費到這檔活動的訊息</p>
        </AppCard>
      </div>
    </template>
  </div>
</template>
