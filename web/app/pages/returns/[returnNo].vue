<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useReturns } from '~/composables/useReturns'
import { useAuthStore } from '~/stores/auth'
import type { ReturnRequestView } from '~/types/api'

/**
 * 退貨單詳情與進度。
 *
 * <p><b>驗收結果誠實顯示，包含「不可再售」。</b>
 * 那一項的錢照退，但商品不會回到可售庫存——
 * 藏起來不說，日後客服被問到時就得臨時解釋一個畫面上從沒出現過的概念。
 */
const route = useRoute()
const returnNo = route.params.returnNo as string

const auth = useAuthStore()
const { findOne, cancel } = useReturns()

const request = ref<ReturnRequestView | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const cancelling = ref(false)
const actionError = ref<string | null>(null)

/** 貨一旦被收下就不能再撤——那會讓買家既沒錢也沒貨 */
const canCancel = computed(
  () => request.value !== null && ['REQUESTED', 'APPROVED'].includes(request.value.status),
)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    request.value = await findOne(returnNo)
  } catch (cause) {
    loadError.value = errorMessage(cause, '無法載入退貨單')
  } finally {
    loading.value = false
  }
}

async function withdraw() {
  cancelling.value = true
  actionError.value = null
  try {
    await cancel(returnNo)
    await load()
  } catch (cause) {
    actionError.value = errorMessage(cause, '撤回失敗')
  } finally {
    cancelling.value = false
  }
}

onMounted(() => {
  if (auth.isAuthenticated) {
    load()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    load()
  }
})

useHead({ title: `退貨 ${returnNo}` })
</script>

<template>
  <div>
    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else-if="request">
      <PageHeader eyebrow="Return" :title="request.returnNo">
        <template #actions>
          <StatusBadge :status="request.status" />
        </template>
      </PageHeader>

      <div class="grid gap-8 lg:grid-cols-[1fr_20rem] lg:items-start">
        <div class="flex flex-col gap-8">
          <section aria-labelledby="progress-heading">
            <h2 id="progress-heading" class="eyebrow mb-4">進度</h2>
            <AppCard class="p-5">
              <ReturnTimeline :request="request" />
            </AppCard>
          </section>

          <section aria-labelledby="items-heading">
            <h2 id="items-heading" class="eyebrow mb-3">退貨品項</h2>
            <ul class="flex flex-col gap-2">
              <li v-for="line in request.lines" :key="line.skuId">
                <AppCard class="flex flex-wrap items-baseline justify-between gap-4 p-4">
                  <div class="min-w-0">
                    <p class="font-medium">{{ line.skuSnapshot }}</p>
                    <p class="mt-1 flex items-baseline gap-1.5 text-sm text-ink-muted">
                      <MoneyText :amount="line.unitPrice" size="sm" tone="muted" />
                      <span class="figure">× {{ line.quantity }}</span>
                    </p>
                    <!-- 不可再售照實說。錢照退，但商品不會回到可售庫存 -->
                    <p
                      v-if="line.restockable === false"
                      class="mt-1 text-xs text-danger"
                    >
                      驗收後判定不可再售，退款不受影響
                    </p>
                  </div>
                  <MoneyText :amount="line.unitPrice * line.quantity" />
                </AppCard>
              </li>
            </ul>
          </section>

          <section aria-labelledby="reason-heading">
            <h2 id="reason-heading" class="eyebrow mb-3">申請資訊</h2>
            <AppCard class="p-4 text-sm">
              <dl class="flex flex-col gap-2">
                <div class="flex gap-3">
                  <dt class="w-20 shrink-0 text-ink-muted">原訂單</dt>
                  <dd>
                    <NuxtLink
                      :to="`/orders/${request.orderNo}`"
                      class="figure text-accent hover:underline"
                    >
                      {{ request.orderNo }} →
                    </NuxtLink>
                  </dd>
                </div>
                <div v-if="request.reasonDetail" class="flex gap-3">
                  <dt class="w-20 shrink-0 text-ink-muted">補充說明</dt>
                  <dd class="min-w-0">{{ request.reasonDetail }}</dd>
                </div>
                <div v-if="request.reviewNote" class="flex gap-3">
                  <dt class="w-20 shrink-0 text-ink-muted">客服回覆</dt>
                  <dd class="min-w-0">{{ request.reviewNote }}</dd>
                </div>
              </dl>
            </AppCard>
          </section>
        </div>

        <AppCard class="p-5 lg:sticky lg:top-24">
          <h2 class="eyebrow mb-3">退款金額</h2>
          <MoneyText :amount="request.refundAmount" size="xl" />
          <p class="mt-2 text-xs text-ink-faint">
            依下單當時的單價計算。
          </p>

          <AppButton
            v-if="canCancel"
            class="mt-6" variant="secondary" block
            :disabled="cancelling" @click="withdraw"
          >
            {{ cancelling ? '撤回中⋯' : '撤回申請' }}
          </AppButton>

          <p
            v-if="actionError"
            class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
            role="alert"
          >
            {{ actionError }}
          </p>

          <NuxtLink
            to="/returns"
            class="mt-4 block text-center text-sm text-ink-muted transition-colors hover:text-ink"
          >
            回退貨列表
          </NuxtLink>
        </AppCard>
      </div>
    </template>

    <EmptyState v-else-if="loadError" :title="loadError">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/returns')">
        回退貨列表
      </AppButton>
    </EmptyState>

    <div v-else class="flex flex-col gap-8">
      <div class="flex flex-col gap-3">
        <SkeletonBlock height="h-3" width="w-16" />
        <SkeletonBlock height="h-8" width="w-64" />
      </div>
      <SkeletonCard variant="row" />
      <SkeletonCard variant="row" />
    </div>
  </div>
</template>
