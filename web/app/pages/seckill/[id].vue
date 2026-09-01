<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useApi } from '~/composables/useApi'
import type { ActivityView, PaymentIntentView } from '~/types/api'

/**
 * 秒殺頁 —— 削峰漏斗的第 0 層。
 *
 * 頁面的靜態部分（商品資訊、活動時間）由 ISR + CDN 承接，
 * 100 萬次瀏覽不該有一次打到 origin。
 * 庫存數字則走獨立的客戶端請求：它變動極快，
 * 快取它只會讓使用者看到過期數字。
 */
const route = useRoute()
const activityId = Number(route.params.id)

const auth = useAuthStore()
const { request } = useApi()

/**
 * SSR 時取一次活動作為首屏內容。
 *
 * 這一份會被 ISR 快取，因此**不能依賴它的庫存數字**——
 * 快取的 HTML 可能是五分鐘前產生的。庫存由客戶端掛載後自行刷新。
 */
const { data: initialActivity } = await useFetch<{ data: ActivityView }>(
  `/api/v1/activities/${activityId}`,
)

const {
  activity, outcome, submitting, serverNow,
  loadActivity, startStockPolling, attempt, reset,
} = useSeckill(activityId)

// 以 SSR 取得的資料作為初始畫面，避免首屏空白
if (initialActivity.value?.data) {
  activity.value = initialActivity.value.data
}

const started = ref(false)
const paymentUrl = ref<string | null>(null)
const paying = ref(false)

onMounted(async () => {
  // 客戶端掛載後立刻重取：SSR 的那份可能來自 CDN 快取，
  // 且時鐘校正需要一次「真實往返」才能算出偏移
  await loadActivity().catch(() => undefined)
  startStockPolling()
})

const soldOut = computed(() => (activity.value?.availableStock ?? 0) <= 0)

async function onAttempt(): Promise<void> {
  if (!auth.isAuthenticated) {
    document.getElementById('auth-panel')?.scrollIntoView({ behavior: 'smooth' })
    return
  }
  await attempt(1)
}

/** 搶到之後直接發起付款，讓整條流程在同一頁走完。 */
async function payNow(orderNo: string): Promise<void> {
  paying.value = true
  try {
    const intent = await request<PaymentIntentView>(`/api/v1/orders/${orderNo}/payments`, {
      method: 'POST',
      authenticated: true,
    })
    paymentUrl.value = intent.paymentUrl
  } catch {
    paymentUrl.value = null
  } finally {
    paying.value = false
  }
}

useHead(() => ({
  title: activity.value ? `${activity.value.productName} — 限時搶購` : '限時搶購',
}))
</script>

<template>
  <main class="mx-auto max-w-3xl px-5 py-10">
    <NuxtLink to="/" class="text-sm text-[var(--ink-muted)] hover:underline">← 回活動列表</NuxtLink>

    <template v-if="activity">
      <!-- 靜態部分：可被 CDN 完全承接 -->
      <header class="mt-4">
        <h1 class="text-3xl font-black tracking-tight">{{ activity.productName }}</h1>
        <p class="mt-2 font-mono text-3xl font-bold text-[var(--danger)]">
          NT$ {{ activity.seckillPrice.toLocaleString() }}
        </p>
        <p class="mt-1 text-sm text-[var(--ink-muted)]">
          每人限購 {{ activity.perUserLimit }} 件
        </p>
      </header>

      <section class="mt-6 rounded border border-[var(--line)] bg-[var(--surface)] p-5">
        <CountdownTimer
          :start-at="activity.startAt"
          :end-at="activity.endAt"
          :server-now="serverNow"
          @started="started = true"
        />

        <!-- 動態部分：獨立請求，不隨頁面快取 -->
        <div class="mt-5">
          <StockIndicator :available="activity.availableStock" :total="activity.totalStock" />
        </div>

        <div class="mt-5">
          <SeckillButton
            :started="started"
            :sold-out="soldOut"
            :submitting="submitting"
            :authenticated="auth.isAuthenticated"
            @attempt="onAttempt"
          />
        </div>

        <!-- 搶購結果 -->
        <div class="mt-4 text-sm" role="status" aria-live="polite">
          <p v-if="outcome.kind === 'processing'" class="text-[var(--ink-muted)]">
            已受理，訂單建立中⋯（訂單號 {{ outcome.orderNo }}）
          </p>

          <div v-else-if="outcome.kind === 'success'" class="rounded bg-emerald-50 p-4">
            <p class="font-semibold text-emerald-800">搶購成功</p>
            <p class="mt-1 text-emerald-700">
              訂單 {{ outcome.orderNo }}，狀態 {{ outcome.order.status }}
            </p>

            <button
              v-if="outcome.order.status === 'PENDING_PAYMENT' && !paymentUrl"
              type="button"
              :disabled="paying"
              class="mt-3 rounded bg-[var(--accent)] px-4 py-2 font-semibold text-white disabled:bg-slate-300"
              @click="payNow(outcome.orderNo)"
            >
              {{ paying ? '前往付款⋯' : '去付款' }}
            </button>

            <p v-if="paymentUrl" class="mt-3 text-emerald-700">
              已建立付款單，模擬閘道將在數秒後回調完成付款。
            </p>
          </div>

          <!--
            逾時不等於失敗。庫存可能已經扣了、訂單也還在建立中，
            只是消費端還沒跟上。讓使用者去訂單頁查，
            而不是留在這裡無限輪詢——那在尖峰時是第二波流量。
          -->
          <p v-else-if="outcome.kind === 'timeout'" class="text-amber-700">
            處理時間較長，請稍後至訂單頁查看（訂單號 {{ outcome.orderNo }}）
          </p>

          <p v-else-if="outcome.kind === 'rejected'" class="text-[var(--danger)]">
            {{ outcome.message }}
            <button type="button" class="ml-2 underline" @click="reset()">重試</button>
          </p>
        </div>
      </section>

      <section id="auth-panel" class="mt-6">
        <AuthPanel />
      </section>
    </template>

    <p v-else class="mt-10 text-[var(--ink-muted)]">載入中⋯</p>
  </main>
</template>
