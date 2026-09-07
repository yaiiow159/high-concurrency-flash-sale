<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useApi } from '~/composables/useApi'
import type { ActivityView, PaymentIntentView } from '~/types/api'

/**
 * 秒殺頁 —— 削峰漏斗的第 0 層。 頁面的靜態部分（商品資訊、活動時間）由 ISR + CDN 承接， 100 萬次瀏覽不該有一次打到 origin。 庫存數字則走獨立的客戶端請求：它變動極快， 快取它只會讓使用者看到過期數字。
 */
const route = useRoute()
const activityId = Number(route.params.id)

const auth = useAuthStore()
const { request } = useApi()

/** SSR 時取一次活動作為首屏內容。 這一份會被 ISR 快取，因此**不能依賴它的庫存數字**—— 快取的 HTML 可能是五分鐘前產生的。庫存由客戶端掛載後自行刷新。 */
const { data: initialActivity } = await useFetch<{ data: ActivityView }>(
  `/api/v1/activities/${activityId}`,
)

const {
  activity, outcome, submitting, serverNow,
  seedFromServerRender, loadActivity, startStockPolling, attempt, reset,
} = useSeckill(activityId)

if (initialActivity.value?.data) {
  seedFromServerRender(initialActivity.value.data)
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
    // 與訂單頁同一條路：導向模擬金流頁，回來時靠回調而非這個導向
    window.location.href = intent.paymentUrl
  } catch {
    paymentUrl.value = null
  } finally {
    paying.value = false
  }
}

/** 排隊提示。 等待秒數為 -1 代表**算不出來**（速率還沒量到），此時只說「排隊中」—— 顯示「約 0 秒」然後讓人等四十分鐘，比誠實承認不知道更糟。 */
const queueHint = computed(() => {
  if (outcome.value.kind !== 'processing') {
    return null
  }
  const queue = outcome.value.queue
  if (!queue) {
    return null
  }
  const ahead = `前面約 ${queue.ahead.toLocaleString()} 筆`
  if (queue.estimatedWaitSeconds < 0) {
    return `${ahead}，時間待估`
  }
  const minutes = Math.ceil(queue.estimatedWaitSeconds / 60)
  return queue.estimatedWaitSeconds < 60
    ? `${ahead}，約 ${queue.estimatedWaitSeconds} 秒`
    : `${ahead}，約 ${minutes} 分鐘`
})

const { seo } = useSeo()
watchEffect(() => {
  const current = activity.value
  seo({
    title: current ? `${current.productName} — 限時搶購` : '限時搶購',
    description: current
      ? `${current.productName} 限時特價 NT$ ${current.seckillPrice}，每人限購 ${current.perUserLimit} 件。`
      : undefined,
    path: `/seckill/${route.params.id}`,
  })
})
</script>

<template>
  <div class="pb-action-bar">
    <template v-if="activity">
      <nav aria-label="麵包屑" class="text-sm text-ink-muted">
        <ol class="flex items-center gap-1.5">
          <li><NuxtLink to="/" class="transition-colors hover:text-accent">首頁</NuxtLink></li>
          <li class="flex items-center gap-1.5">
            <span aria-hidden="true" class="text-ink-faint">/</span>
            <span class="text-ink">限時搶購</span>
          </li>
        </ol>
      </nav>

      <AppCard class="mt-4 overflow-hidden">
        <!-- 活動狀態列：整頁只有這裡與搶購鈕用品牌漸層 -->
        <div class="bg-promo flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-white sm:px-6">
          <div class="flex items-center gap-2">
            <span class="flex items-center gap-1 rounded-sm bg-white/20 px-2 py-0.5 text-xs font-extrabold">
              <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="currentColor" aria-hidden="true">
                <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />
              </svg>
              限時搶購
            </span>
            <span class="text-sm font-semibold">
              每人限購 <span class="figure">{{ activity.perUserLimit.toLocaleString() }}</span> 件
            </span>
          </div>
          <CountdownTimer
            :start-at="activity.startAt"
            :end-at="activity.endAt"
            :server-now="serverNow"
            tone="light"
            size="lg"
            @started="started = true"
          />
        </div>

        <div class="grid gap-6 p-4 sm:p-6 lg:grid-cols-[minmax(0,26rem)_minmax(0,1fr)] lg:gap-10">
          <ProductTile
            :seed="activity.skuId" :label="activity.productName" class="w-full rounded"
          />

          <div class="min-w-0">
            <h1 class="text-xl font-extrabold leading-snug tracking-tight sm:text-2xl">
              {{ activity.productName }}
            </h1>

            <div class="mt-4 rounded bg-danger-soft/70 px-4 py-3.5">
              <p class="eyebrow text-danger/70">秒殺價</p>
              <MoneyText :amount="activity.seckillPrice" size="xl" tone="danger" class="mt-0.5" />
            </div>

            <!-- 庫存是獨立請求，不隨頁面快取 -->
            <StockIndicator
              class="mt-5"
              :available="activity.availableStock" :total="activity.totalStock"
            />

            <div class="mt-6 hidden lg:block">
              <SeckillButton
                :started="started"
                :sold-out="soldOut"
                :submitting="submitting"
                :authenticated="auth.isAuthenticated"
                @attempt="onAttempt"
              />
            </div>

            <div class="mt-4 text-sm" role="status" aria-live="polite">
              <p v-if="outcome.kind === 'processing'" class="text-ink-muted">
                已受理，訂單建立中⋯
                <span class="figure block">{{ outcome.orderNo }}</span>
                <span v-if="queueHint" class="mt-1 block text-xs text-ink-faint">{{ queueHint }}</span>
              </p>

              <div
                v-else-if="outcome.kind === 'success'"
                class="rounded-sm border border-ok/40 bg-ok-soft p-4"
              >
                <p class="font-bold text-ok">搶購成功</p>
                <p class="figure mt-1 text-xs text-ink-muted">{{ outcome.orderNo }}</p>
                <ul class="mt-2 flex flex-col gap-1">
                  <li v-for="line in outcome.order.lines" :key="line.skuId" class="text-ink-muted">
                    {{ line.skuSnapshot }}
                    <span class="figure">× {{ line.quantity }}</span>
                  </li>
                </ul>

                <AppButton
                  v-if="outcome.order.status === 'PENDING_PAYMENT' && !paymentUrl"
                  class="mt-4" size="sm" block :disabled="paying"
                  @click="payNow(outcome.orderNo)"
                >
                  {{ paying ? '前往付款⋯' : '去付款' }}
                </AppButton>

                <p v-if="paymentUrl" class="mt-3 text-xs text-ink-muted">
                  已建立付款單，模擬閘道將在數秒後回調完成付款。
                </p>
                <NuxtLink
                  :to="`/orders/${outcome.orderNo}`"
                  class="mt-2 block text-accent hover:underline"
                >
                  查看訂單 →
                </NuxtLink>
              </div>

              <!-- 逾時不等於失敗：庫存可能已扣、訂單也在建立，只是消費端還沒跟上 -->
              <div
                v-else-if="outcome.kind === 'timeout'"
                class="rounded-sm border border-line bg-sunken p-4 text-ink-muted"
              >
                <p>處理時間較長，請稍後至訂單頁查看。</p>
                <NuxtLink
                  :to="`/orders/${outcome.orderNo}`"
                  class="figure mt-1 block text-accent hover:underline"
                >
                  {{ outcome.orderNo }} →
                </NuxtLink>
              </div>

              <p v-else-if="outcome.kind === 'rejected'" class="text-danger">
                {{ outcome.message }}
                <button type="button" class="ml-2 underline" @click="reset()">重試</button>
              </p>
            </div>

            <div v-if="!auth.isAuthenticated" id="auth-panel" class="mt-6">
              <AuthPanel />
            </div>
          </div>
        </div>
      </AppCard>

      <StickyActionBar>
        <template #info>
          <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
          <p class="figure mt-0.5 text-xs text-ink-faint">
            餘 {{ activity.availableStock }} / {{ activity.totalStock }}
          </p>
        </template>
        <template #action>
          <div class="w-40">
            <SeckillButton
              :started="started"
              :sold-out="soldOut"
              :submitting="submitting"
              :authenticated="auth.isAuthenticated"
              @attempt="onAttempt"
            />
          </div>
        </template>
      </StickyActionBar>
    </template>

    <div v-else class="grid gap-6 lg:grid-cols-[minmax(0,26rem)_minmax(0,1fr)]">
      <SkeletonBlock height="aspect-square" width="w-full" rounded />
      <div class="flex flex-col gap-4">
        <SkeletonBlock height="h-8" width="w-2/3" />
        <SkeletonBlock height="h-20" width="w-full" rounded />
        <SkeletonBlock height="h-14" width="w-full" rounded />
      </div>
    </div>
  </div>
</template>
