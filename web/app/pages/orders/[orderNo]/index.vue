<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import { useReturns } from '~/composables/useReturns'
import type { OrderView, ShipmentView } from '~/types/api'

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
const { inspect } = useReturns()

const order = ref<OrderView | null>(null)
/** 出貨進度另外取：訂單尚未付款時還沒有出貨單，查不到是正常的 */
const shipment = ref<ShipmentView | null>(null)
/**
 * 這張訂單現在還能不能退。
 *
 * 只憑訂單狀態判斷會誤導：品項全部申請過退貨之後，訂單仍然是 COMPLETED，
 * 但已經沒有東西可退了。那時還顯示「申請退貨」，
 * 按下去只會得到一個空表單。
 */
const canReturn = ref(false)
const loadError = ref<string | null>(null)
const paying = ref(false)

/**
 * 三個請求<b>並行發出</b>。
 *
 * 它們都只需要網址上的 orderNo，彼此不相依——先前是三個接連的 await，
 * 在 200ms 延遲的行動網路上就是 600ms 才看得到畫面，而其中 400ms
 * 純粹是排隊等前一個回來。
 *
 * 出貨單與退貨資格各自 catch：訂單還沒付款時本來就沒有出貨單，
 * 而任一個附屬查詢失敗都不該讓整張訂單看不到。
 */
async function load() {
  const [orderResult, shipmentResult, returnable] = await Promise.all([
    request<OrderView>(`/api/v1/orders/${orderNo}`, { authenticated: true })
      .catch((error: { message?: string }) => {
        loadError.value = error.message ?? '無法載入訂單'
        return null
      }),
    request<ShipmentView>(`/api/v1/orders/${orderNo}/shipment`, { authenticated: true })
      .catch(() => null),
    inspect(orderNo).then((view) => view.returnable).catch(() => false),
  ])

  order.value = orderResult
  shipment.value = shipmentResult
  canReturn.value = returnable
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

onMounted(load)
useHead({ title: `訂單 ${orderNo}` })
</script>

<template>
  <div>
    <template v-if="order">
      <PageHeader eyebrow="Order" :title="order.orderNo">
        <template #actions>
          <StatusBadge :status="order.status" />
        </template>
      </PageHeader>

      <p v-if="order.closeReason" class="-mt-4 mb-6 text-sm text-ink-muted">
        {{ order.closeReason }}
      </p>

      <div class="grid gap-8 lg:grid-cols-[1fr_20rem] lg:items-start">
        <div class="flex flex-col gap-8">
          <section aria-labelledby="lines-heading">
            <h2 id="lines-heading" class="eyebrow mb-3">訂單內容</h2>
            <ul class="flex flex-col gap-2">
              <li v-for="line in order.lines" :key="line.skuId">
                <AppCard class="flex flex-wrap items-baseline justify-between gap-4 p-4">
                  <!-- 顯示的是下單當下的快照，不是商品現在的名稱。
                       商家日後改名或調價，這張訂單不能跟著變。 -->
                  <div>
                    <p class="font-medium">{{ line.skuSnapshot }}</p>
                    <p class="mt-1 flex items-baseline gap-1.5 text-sm text-ink-muted">
                      <MoneyText :amount="line.unitPrice" size="sm" tone="muted" />
                      <span class="figure">× {{ line.quantity }}</span>
                    </p>
                  </div>
                  <MoneyText :amount="line.subtotal" />
                </AppCard>
              </li>
            </ul>
          </section>

          <section v-if="order.shipping" aria-labelledby="shipping-heading">
            <h2 id="shipping-heading" class="eyebrow mb-3">寄送資訊</h2>
            <AppCard class="p-4">
              <p>
                <span class="font-medium">{{ order.shipping.recipientName }}</span>
                <span class="figure ml-3 text-sm text-ink-faint">{{ order.shipping.phone }}</span>
              </p>
              <p class="mt-1 text-sm text-ink-muted">{{ order.shipping.fullAddress }}</p>
            </AppCard>
          </section>

          <section v-if="shipment" aria-labelledby="tracking-heading">
            <h2 id="tracking-heading" class="eyebrow mb-3">物流進度</h2>
            <AppCard class="p-4">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p class="font-medium">{{ shipment.carrierName ?? '尚未指派承運商' }}</p>
                  <p v-if="shipment.trackingNumber" class="figure mt-1 text-sm text-ink-muted">
                    {{ shipment.trackingNumber }}
                  </p>
                </div>
                <StatusBadge :status="shipment.status" />
              </div>
              <p v-if="shipment.failureReason" class="mt-3 text-sm text-danger">
                {{ shipment.failureReason }}
              </p>
              <a
                v-if="shipment.trackingUrl"
                :href="shipment.trackingUrl" target="_blank" rel="noopener noreferrer"
                class="mt-3 inline-block text-sm text-accent hover:underline"
              >
                到承運商網站追蹤 →
              </a>
            </AppCard>
          </section>
        </div>

        <AppCard class="p-5 lg:sticky lg:top-24">
          <h2 class="eyebrow mb-4">訂單金額</h2>
          <MoneyText :amount="order.totalAmount" size="xl" />

          <AppButton
            v-if="order.status === 'PENDING_PAYMENT'"
            class="mt-6" size="lg" block :disabled="paying" @click="pay"
          >
            {{ paying ? '前往付款⋯' : '前往付款' }}
          </AppButton>

          <!-- 退貨是次要動作，用 secondary：它不是我們希望使用者做的事，
               但也不該藏起來讓人找不到而只好打客服 -->
          <AppButton
            v-if="canReturn"
            class="mt-6" variant="secondary" block
            @click="navigateTo(`/orders/${orderNo}/return`)"
          >
            申請退貨
          </AppButton>

          <NuxtLink
            to="/products"
            class="mt-4 block text-center text-sm text-ink-muted transition-colors hover:text-ink"
          >
            繼續購物
          </NuxtLink>
        </AppCard>
      </div>
    </template>

    <EmptyState v-else-if="loadError" :title="loadError">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        回商品列表
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
