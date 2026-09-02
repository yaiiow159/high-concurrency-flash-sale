<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import type { OrderView } from '~/types/api'

/**
 * 訂單詳情與付款。
 *
 * 這一頁**不做 ISR**：訂單是每個使用者專屬的資料，
 * 被 CDN 快取等於把別人的訂單發給下一個訪客。
 * 只有匿名且對所有人相同的內容才適合快取。
 */
const route = useRoute()
const orderNo = route.params.orderNo as string
const { request } = useApi()

const order = ref<OrderView | null>(null)
const loadError = ref<string | null>(null)
const paying = ref(false)

async function load() {
  try {
    order.value = await request<OrderView>(`/api/v1/orders/${orderNo}`, {
      authenticated: true,
    })
  } catch (error) {
    loadError.value = (error as { message?: string }).message ?? '無法載入訂單'
  }
}

async function pay() {
  paying.value = true
  try {
    const intent = await request<{ payUrl: string }>(
      `/api/v1/orders/${orderNo}/payments`,
      { method: 'POST', authenticated: true, body: { method: 'CREDIT_CARD' } },
    )
    // 導向模擬金流頁；真實金流同樣是離站，回來時靠回調而非這個導向
    window.location.href = intent.payUrl
  } catch (error) {
    loadError.value = (error as { message?: string }).message ?? '無法發起付款'
    paying.value = false
  }
}

const statusLabel = computed(() => {
  switch (order.value?.status) {
    case 'PENDING_PAYMENT': return '待付款'
    case 'PAID': return '已付款'
    case 'CANCELLED': return '已取消'
    case 'FAILED': return '建立失敗'
    default: return order.value?.status ?? ''
  }
})

onMounted(load)
useHead({ title: `訂單 ${orderNo}` })
</script>

<template>
  <main class="mx-auto max-w-3xl px-5 py-10">
    <NuxtLink to="/products" class="text-sm text-[var(--ink-muted)] hover:underline">
      ← 繼續購物
    </NuxtLink>

    <div v-if="order" class="mt-4">
      <header class="flex flex-wrap items-baseline justify-between gap-3">
        <h1 class="font-mono text-2xl font-bold">{{ order.orderNo }}</h1>
        <span
          class="rounded border px-3 py-1 text-sm"
          :class="order.status === 'PAID'
            ? 'border-[var(--accent)] text-[var(--accent)]'
            : 'border-[var(--line)] text-[var(--ink-muted)]'"
        >
          {{ statusLabel }}
        </span>
      </header>

      <p v-if="order.closeReason" class="mt-2 text-sm text-[var(--ink-muted)]">
        {{ order.closeReason }}
      </p>

      <ul class="mt-6 flex flex-col gap-2">
        <li
          v-for="line in order.lines"
          :key="line.skuId"
          class="flex items-baseline justify-between gap-4 rounded border border-[var(--line)]
                 bg-[var(--surface)] p-4"
        >
          <!-- 顯示的是下單當下的快照，不是商品現在的名稱。
               商家日後改名或調價，這張訂單不能跟著變。 -->
          <div>
            <div class="font-medium">{{ line.skuSnapshot }}</div>
            <div class="mt-1 font-mono text-sm text-[var(--ink-muted)]">
              NT$ {{ line.unitPrice.toLocaleString() }} × {{ line.quantity }}
            </div>
          </div>
          <div class="tabular font-mono font-semibold">
            NT$ {{ line.subtotal.toLocaleString() }}
          </div>
        </li>
      </ul>

      <p class="mt-6 text-right font-mono text-2xl font-bold">
        NT$ {{ (order.totalAmount ?? 0).toLocaleString() }}
      </p>

      <!-- 顯示的是下單當下的地址快照。使用者之後改了地址簿甚至把那筆刪掉，
           這裡都不會變——那是出貨紀錄，不是一個會跟著更新的欄位。 -->
      <section v-if="order.shipping" class="mt-8" aria-labelledby="shipping-heading">
        <h2 id="shipping-heading" class="text-sm font-semibold text-[var(--ink-muted)]">
          寄送資訊
        </h2>
        <div class="mt-2 rounded border border-[var(--line)] bg-[var(--surface)] p-4">
          <div>
            <span class="font-medium">{{ order.shipping.recipientName }}</span>
            <span class="ml-3 font-mono text-sm text-[var(--ink-muted)]">
              {{ order.shipping.phone }}
            </span>
          </div>
          <p class="mt-1 text-sm text-[var(--ink-muted)]">
            {{ order.shipping.fullAddress }}
          </p>
        </div>
      </section>

      <button
        v-if="order.status === 'PENDING_PAYMENT'"
        type="button"
        :disabled="paying"
        class="mt-6 w-full rounded bg-[var(--accent)] px-6 py-4 font-semibold text-white
               transition disabled:opacity-40"
        @click="pay"
      >
        {{ paying ? '前往付款⋯' : '前往付款' }}
      </button>
    </div>

    <p v-else-if="loadError" class="mt-6 text-[var(--danger)]" role="alert">
      {{ loadError }}
    </p>
    <p v-else class="mt-6 text-[var(--ink-muted)]">載入中⋯</p>
  </main>
</template>
