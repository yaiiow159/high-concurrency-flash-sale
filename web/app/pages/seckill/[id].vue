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
  <div class="pb-action-bar">
    <template v-if="activity">
      <!-- 靜態部分：可被 CDN 完全承接 -->
      <PageHeader eyebrow="限時搶購" :title="activity.productName">
        <template #actions>
          <NuxtLink to="/" class="text-sm text-ink-muted transition-colors hover:text-ink">
            ← 回活動列表
          </NuxtLink>
        </template>
      </PageHeader>

      <div class="grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem] lg:items-start lg:gap-12">
        <div>
          <ProductTile
            :seed="activity.skuId" :label="activity.productName"
            ratio="wide" class="mb-6 w-full rounded"
          />
          <MoneyText :amount="activity.seckillPrice" size="xl" tone="danger" />
          <p class="mt-2 text-sm text-ink-muted">
            每人限購 <span class="figure">{{ activity.perUserLimit }}</span> 件
          </p>

          <AppCard class="mt-6 p-5">
            <CountdownTimer
              :start-at="activity.startAt"
              :end-at="activity.endAt"
              :server-now="serverNow"
              @started="started = true"
            />
            <!-- 動態部分：獨立請求，不隨頁面快取 -->
            <div class="mt-6">
              <StockIndicator :available="activity.availableStock" :total="activity.totalStock" />
            </div>
          </AppCard>
        </div>

        <AppCard class="hidden p-5 lg:sticky lg:top-24 lg:block">
          <SeckillButton
            :started="started"
            :sold-out="soldOut"
            :submitting="submitting"
            :authenticated="auth.isAuthenticated"
            @attempt="onAttempt"
          />

          <div class="mt-4 text-sm" role="status" aria-live="polite">
            <p v-if="outcome.kind === 'processing'" class="text-ink-muted">
              已受理，訂單建立中⋯
              <span class="figure block">{{ outcome.orderNo }}</span>
            </p>

            <div
              v-else-if="outcome.kind === 'success'"
              class="rounded-sm border border-ok/40 bg-ok-soft p-4"
            >
              <p class="font-semibold text-ok">搶購成功</p>
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
            </div>

            <!--
              逾時不等於失敗。庫存可能已經扣了、訂單也還在建立中，
              只是消費端還沒跟上。讓使用者去訂單頁查，
              而不是留在這裡無限輪詢——那在尖峰時是第二波流量。
            -->
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

          <div v-if="!auth.isAuthenticated" id="auth-panel" class="mt-5">
            <AuthPanel />
          </div>
        </AppCard>
      </div>

      <!--
        手機：搶購鈕放進底部固定列，結果與登入面板留在內容流。
        桌機的側欄已經固定在畫面上，不需要再蓋一條。
      -->
      <div class="mt-8 lg:hidden">
        <div v-if="!auth.isAuthenticated" id="auth-panel-mobile">
          <AuthPanel />
        </div>

        <div class="mt-4 text-sm" role="status" aria-live="polite">
          <p v-if="outcome.kind === 'processing'" class="text-ink-muted">
            已受理，訂單建立中⋯
            <span class="figure block">{{ outcome.orderNo }}</span>
          </p>

          <div
            v-else-if="outcome.kind === 'success'"
            class="rounded-sm border border-ok/40 bg-ok-soft p-4"
          >
            <p class="font-semibold text-ok">搶購成功</p>
            <NuxtLink
              :to="`/orders/${outcome.orderNo}`"
              class="figure mt-1 block text-accent hover:underline"
            >
              {{ outcome.orderNo }} →
            </NuxtLink>
          </div>

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
      </div>

      <StickyActionBar>
        <template #info>
          <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
          <p class="figure mt-0.5 text-xs text-ink-faint">
            餘 {{ activity.availableStock }} / {{ activity.totalStock }}
          </p>
        </template>
        <template #action>
          <div class="w-36">
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

    <p v-else class="text-ink-muted">載入中⋯</p>
  </div>
</template>
