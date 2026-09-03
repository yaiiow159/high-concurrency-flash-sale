<script setup lang="ts">
import { useReturns } from '~/composables/useReturns'
import { useAuthStore } from '~/stores/auth'
import type { DeepReadonly } from 'vue'
import type { ReturnRequestView } from '~/types/api'

/**
 * composable 刻意把清單設為 readonly——列表頁只該讀，改動一律走 API。
 * 因此這裡的輔助函式也宣告成 readonly，而不是回頭放寬 composable：
 * 為了讓一個 helper 的型別好寫而拆掉封裝，是本末倒置。
 */
type ReadonlyReturn = DeepReadonly<ReturnRequestView>

/**
 * 我的退貨。
 *
 * 與訂單列表一樣<b>不做 SSR、不做 ISR</b>——這是每個人專屬的資料，
 * 進了被快取的 HTML 就等於發給下一個訪客。
 */
const auth = useAuthStore()
const { returns, loading, error, load } = useReturns()

/** 一張退貨單的品項摘要：第一件的名稱，多件時補「等 N 項」 */
function summarise(request: ReadonlyReturn): string {
  const first = request.lines[0]
  if (!first) {
    return '（無品項）'
  }
  return request.lines.length > 1
    ? `${first.skuSnapshot} 等 ${request.lines.length} 項`
    : first.skuSnapshot
}

/**
 * 一句話說明「現在輪到誰做什麼」。
 *
 * 只顯示狀態名稱不夠——「已核准」之後買家要不要做事，
 * 取決於這張單需不需要寄回，而那不是狀態本身看得出來的。
 */
function nextAction(request: ReadonlyReturn): string {
  switch (request.status) {
    case 'REQUESTED':
      return '客服審核中'
    case 'APPROVED':
      return request.requiresGoodsReturn ? '請把商品寄回' : '等待退款'
    case 'RECEIVED':
      return '已驗收，等待退款'
    case 'REFUNDED':
      return '款項退回中'
    case 'REJECTED':
      return request.reviewNote ?? '申請未通過'
    default:
      return ''
  }
}

function formatDate(value: string | null): string {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
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

useHead({ title: '我的退貨' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Returns"
      title="我的退貨"
      description="退款金額依下單當時的單價計算，不受商家事後調價影響。"
    />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <div v-if="loading" class="flex flex-col gap-3">
        <SkeletonCard v-for="n in 3" :key="n" variant="row" />
      </div>

      <p
        v-else-if="error"
        class="rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>

      <ul v-else-if="returns.length > 0" class="flex flex-col gap-3">
        <li v-for="request in returns" :key="request.returnNo">
          <NuxtLink :to="`/returns/${request.returnNo}`" class="block">
            <AppCard interactive class="p-5">
              <div class="flex flex-wrap items-start justify-between gap-4">
                <div class="min-w-0">
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="figure text-sm text-ink-muted">{{ request.returnNo }}</span>
                    <StatusBadge :status="request.status" />
                  </div>
                  <p class="mt-2 truncate font-medium">{{ summarise(request) }}</p>
                  <p class="mt-1 text-sm text-ink-muted">{{ nextAction(request) }}</p>
                  <p class="mt-1 text-xs text-ink-faint">{{ formatDate(request.createdAt) }}</p>
                </div>
                <MoneyText :amount="request.refundAmount" size="lg" />
              </div>
            </AppCard>
          </NuxtLink>
        </li>
      </ul>

      <EmptyState
        v-else
        title="還沒有任何退貨紀錄。"
        hint="需要退貨的話，從訂單頁面提出申請。"
      >
        <AppButton variant="secondary" size="sm" @click="navigateTo('/orders')">
          去看我的訂單
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
