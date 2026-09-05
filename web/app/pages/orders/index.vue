<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { OrderView } from '~/types/api'

/**
 * 我的訂單。
 *
 * <p><b>不做 SSR、不做 ISR</b>——訂單是每個人專屬的資料，
 * 進了被快取的 HTML 就等於發給下一個訪客。
 *
 * <p>分頁用「載入更多」而不是頁碼：訂單是時間序的清單，
 * 使用者要找的通常是最近幾筆，很少有人會跳到第 7 頁。
 * 頁碼會多出「目前在第幾頁」這個要維護的狀態，換不到什麼。
 */
const auth = useAuthStore()
const { request } = useApi()

const PAGE_SIZE = 20

/**
 * 狀態篩選。
 *
 * 在**伺服器端**篩，不是撈回來再過濾——「待付款」這種少數狀態
 * 用前端過濾會需要翻很多頁才湊得滿一頁，而使用者只會看到一個
 * 幾乎空白的清單。
 */
const STATUS_FILTERS = [
  { value: null, label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '待出貨' },
  { value: 'SHIPPED', label: '待收貨' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CLOSED', label: '已關閉' },
] as const

const activeStatus = ref<string | null>(null)

const orders = ref<OrderView[]>([])
const loading = ref(false)
const loadingMore = ref(false)
const error = ref<string | null>(null)
const page = ref(0)
const reachedEnd = ref(false)

async function load(reset = true) {
  if (reset) {
    loading.value = true
    page.value = 0
    reachedEnd.value = false
  } else {
    loadingMore.value = true
  }
  error.value = null

  try {
    const statusParam = activeStatus.value === null ? '' : `&status=${activeStatus.value}`
    const batch = await request<OrderView[]>(
      `/api/v1/orders?page=${page.value}${statusParam}&size=${PAGE_SIZE}`,
      { authenticated: true },
    )
    orders.value = reset ? batch : [...orders.value, ...batch]
    // 回傳不足一頁就代表沒有更多了，不需要多打一次空的請求確認
    reachedEnd.value = batch.length < PAGE_SIZE
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入訂單')
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

async function loadMore() {
  page.value += 1
  await load(false)
}

/** 一張訂單的品項摘要：第一件的名稱，多件時補「等 N 件」 */
function summarise(order: OrderView): string {
  const first = order.lines[0]
  if (!first) {
    return '（無品項）'
  }
  return order.lines.length > 1
    ? `${first.skuSnapshot} 等 ${order.lines.length} 項`
    : first.skuSnapshot
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
// 換篩選要重新從第一頁抓，而不是把新結果接在舊清單後面
watch(activeStatus, () => { void load(true) })

watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    load()
  }
})

useHead({ title: '我的訂單' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Orders"
      title="我的訂單"
      description="顯示的商品名稱與金額都是下單當下的快照，不會隨商家改名或調價而變動。"
    />

    <nav v-if="auth.isAuthenticated" class="mb-6 flex flex-wrap gap-2" aria-label="訂單狀態篩選">
      <button
        v-for="filter in STATUS_FILTERS"
        :key="filter.label"
        type="button"
        class="rounded-full border px-3.5 py-1.5 text-sm transition-colors"
        :class="activeStatus === filter.value
          ? 'border-accent bg-accent-soft font-medium text-accent'
          : 'border-line bg-surface text-ink-muted hover:border-line-strong hover:text-ink'"
        @click="activeStatus = filter.value"
      >
        {{ filter.label }}
      </button>
    </nav>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <!-- 骨架屏保留版面形狀，內容到位時不會位移 -->
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

      <template v-else-if="orders.length > 0">
        <ul class="flex flex-col gap-3">
          <li v-for="order in orders" :key="order.orderNo">
            <NuxtLink :to="`/orders/${order.orderNo}`" class="block">
              <AppCard interactive class="p-5">
                <div class="flex flex-wrap items-start justify-between gap-4">
                  <div class="min-w-0">
                    <div class="flex flex-wrap items-center gap-2">
                      <span class="figure text-sm text-ink-muted">{{ order.orderNo }}</span>
                      <StatusBadge :status="order.status" />
                    </div>
                    <p class="mt-2 truncate font-medium">{{ summarise(order) }}</p>
                    <p class="mt-1 text-xs text-ink-faint">{{ formatDate(order.createdAt) }}</p>
                  </div>
                  <MoneyText :amount="order.totalAmount" size="lg" />
                </div>
              </AppCard>
            </NuxtLink>
          </li>
        </ul>

        <div v-if="!reachedEnd" class="mt-6 flex justify-center">
          <AppButton variant="secondary" :disabled="loadingMore" @click="loadMore">
            {{ loadingMore ? '載入中⋯' : '載入更多' }}
          </AppButton>
        </div>
      </template>

      <EmptyState v-else title="還沒有任何訂單。" hint="逛逛商品，第一筆就從這裡開始。">
        <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
          去逛商品
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
