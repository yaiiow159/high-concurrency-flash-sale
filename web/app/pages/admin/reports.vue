<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'

definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

interface Summary {
  paidOrders: number
  revenue: number
  refunded: number
  netRevenue: number
  averageOrderValue: number
  itemsSold: number
}
interface DailyPoint { date: string, paidOrders: number, revenue: number }
interface TopProduct { productId: number, productName: string, quantity: number, revenue: number }

const { request } = useApi()

/** 預設看最近 30 天：不給預設值的話這一頁一開啟就是空的。 */
const today = new Date().toISOString().slice(0, 10)
const monthAgo = new Date(Date.now() - 29 * 86400_000).toISOString().slice(0, 10)
const from = ref(monthAgo)
const to = ref(today)

const summary = ref<Summary | null>(null)
const daily = ref<DailyPoint[]>([])
const top = ref<TopProduct[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  const query = `from=${from.value}&to=${to.value}`
  try {
    const [s, d, t] = await Promise.all([
      request<Summary>(`/api/v1/admin/reports/sales/summary?${query}`, { authenticated: true }),
      request<DailyPoint[]>(`/api/v1/admin/reports/sales/daily?${query}`, { authenticated: true }),
      request<TopProduct[]>(`/api/v1/admin/reports/sales/top-products?${query}&limit=10`,
        { authenticated: true }),
    ])
    summary.value = s
    daily.value = d
    top.value = t
  } catch (cause) {
    error.value = errorMessage(cause, '讀取報表失敗')
  } finally {
    loading.value = false
  }
}

/** 走勢用純 CSS 長條圖。為了一張圖引進圖表函式庫不划算。 */
const maxRevenue = computed(() =>
  Math.max(1, ...daily.value.map((point) => Number(point.revenue))))

const money = (value: number | string) =>
  new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 }).format(Number(value))

onMounted(load)
useHead({ title: '銷售報表 — 後台' })
</script>

<template>
  <div class="flex flex-col gap-6">
    <AdminPageHeader
      title="銷售報表"
      description="只計已付款的訂單。待付款的還不是營收，算進去會在關單後自己往下掉。"
    />

    <div class="flex flex-wrap items-end gap-3">
      <label class="text-xs text-ink-faint">
        開始
        <input v-model="from" type="date" class="field mt-1">
      </label>
      <label class="text-xs text-ink-faint">
        結束
        <input v-model="to" type="date" class="field mt-1">
      </label>
      <AppButton size="sm" variant="secondary" @click="load">查詢</AppButton>
    </div>

    <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger">{{ error }}</p>

    <dl v-if="summary" class="grid grid-cols-2 gap-3 lg:grid-cols-3">
      <AppCard v-for="card in [
        { label: '已付款訂單', value: summary.paidOrders, prefix: '' },
        { label: '營收', value: money(summary.revenue), prefix: 'NT$ ' },
        { label: '退款', value: money(summary.refunded), prefix: 'NT$ ' },
        { label: '淨營收', value: money(summary.netRevenue), prefix: 'NT$ ' },
        { label: '平均客單價', value: money(summary.averageOrderValue), prefix: 'NT$ ' },
        { label: '售出件數', value: summary.itemsSold, prefix: '' },
      ]" :key="card.label" class="p-4">
        <dt class="eyebrow">{{ card.label }}</dt>
        <dd class="figure mt-1 text-xl font-semibold">{{ card.prefix }}{{ card.value }}</dd>
      </AppCard>
    </dl>

    <section v-if="daily.length > 0">
      <h2 class="mb-3 text-lg font-semibold">逐日營收</h2>
      <ul class="flex h-40 items-end gap-1 overflow-x-auto rounded-sm border border-line
                 bg-surface p-3 shadow-rest">
        <li
          v-for="point in daily"
          :key="point.date"
          class="flex min-w-[14px] flex-1 flex-col items-center justify-end gap-1"
          :title="`${point.date}：NT$ ${money(point.revenue)}（${point.paidOrders} 筆）`"
        >
          <div
            class="w-full rounded-t-sm bg-accent/80"
            :style="{ height: `${(Number(point.revenue) / maxRevenue) * 100}%` }"
          />
        </li>
      </ul>
      <p class="mt-1.5 text-xs text-ink-faint">
        {{ daily[0]?.date }} — {{ daily[daily.length - 1]?.date }}，滑過長條看當日數字
      </p>
    </section>

    <section v-if="top.length > 0">
      <h2 class="mb-3 text-lg font-semibold">熱銷排行</h2>
      <div class="overflow-x-auto">
        <table class="w-full min-w-[420px] text-sm">
          <thead class="text-left text-xs text-ink-faint">
            <tr>
              <th class="pb-2">商品</th>
              <th class="pb-2 text-right">售出</th>
              <th class="pb-2 text-right">金額</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="product in top" :key="product.productId" class="border-t border-line">
              <td class="py-2">
                <NuxtLink :to="`/products/${product.productId}`" class="hover:text-accent">
                  {{ product.productName }}
                </NuxtLink>
              </td>
              <td class="figure py-2 text-right">{{ product.quantity }}</td>
              <td class="figure py-2 text-right">NT$ {{ money(product.revenue) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="mt-2 text-xs text-ink-faint">金額為分攤折扣後的實付，不是定價。</p>
    </section>

    <EmptyState v-if="!loading && summary?.paidOrders === 0" title="這段期間沒有已付款的訂單。" />
  </div>
</template>
