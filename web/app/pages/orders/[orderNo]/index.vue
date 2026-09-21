<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useReturns } from '~/composables/useReturns'
import { useReviews } from '~/composables/useReviews'
import { useCartStore } from '~/stores/cart'
import { describeQueue } from '~/utils/describeQueue'
import type { OrderView, PaymentIntentView, PaymentMethod, ShipmentView } from '~/types/api'

/** 訂單詳情與付款。 這一頁**不做 ISR**：訂單是每個使用者專屬的資料， 被 CDN 快取等於把別人的訂單發給下一個訪客。 只有匿名且對所有人相同的內容才適合快取。 */
const route = useRoute()
const orderNo = route.params.orderNo as string
const { request } = useApi()
const { inspect } = useReturns()
const { reviewable } = useReviews()
const cart = useCartStore()

const order = ref<OrderView | null>(null)
/** 出貨進度另外取：訂單尚未付款時還沒有出貨單，查不到是正常的 */
const shipment = ref<ShipmentView | null>(null)
/** 這張訂單現在還能不能退。 只憑訂單狀態判斷會誤導：品項全部申請過退貨之後，訂單仍然是 COMPLETED， 但已經沒有東西可退了。那時還顯示「申請退貨」， 按下去只會得到一個空表單。 */
const canReturn = ref(false)
/**
 * 這張訂單還有沒有東西可以評價。 與 canReturn 同一個道理：只看訂單是不是 COMPLETED 會誤導—— 品項全部評價過之後訂單仍然是 COMPLETED，那時還顯示「撰寫評價」， 按下去只會得到一個空清單。
 */
const canReview = ref(false)
const loadError = ref<string | null>(null)
const paying = ref(false)
const paymentMethod = ref<PaymentMethod>('CREDIT_CARD')
/** 倒數歸零。關單是排程做的，最多晚三十秒——這段空窗不該還讓人按得下付款 */
const paymentExpired = ref(false)
const cancelOpen = ref(false)
const cancelling = ref(false)

/**
 * 四個請求<b>並行發出</b>。 它們都只需要網址上的 orderNo，彼此不相依——先前是三個接連的 await， 在 200ms 延遲的行動網路上就是 600ms 才看得到畫面，而其中 400ms 純粹是排隊等前一個回來。 出貨單、退貨資格與評價資格各自 catch：訂單還沒付款時本來就沒有出貨單， 而任一個附屬查詢失敗都不該讓整張訂單看不到。
 */
async function load() {
  const [orderResult, shipmentResult, returnable, reviewableNow] = await Promise.all([
    request<OrderView>(`/api/v1/orders/${orderNo}`, { authenticated: true })
      .catch((error: { message?: string }) => {
        loadError.value = error.message ?? '無法載入訂單'
        return null
      }),
    request<ShipmentView>(`/api/v1/orders/${orderNo}/shipment`, { authenticated: true })
      .catch(() => null),
    inspect(orderNo).then((view) => view.returnable).catch(() => false),
    reviewable(orderNo).then((view) => view.reviewable).catch(() => false),
  ])

  order.value = orderResult
  paymentExpired.value = orderResult?.status === 'PENDING_PAYMENT'
    && orderResult.paymentRemainingSeconds === 0
  shipment.value = shipmentResult
  canReturn.value = returnable
  canReview.value = reviewableNow
  if (orderResult?.processing) {
    void pollUntilCreated()
  }
}

/**
 * 秒殺訂單還在佇列裡時，後端回的是一張只有單號的「建立中」訂單。
 * 輪詢有上限：停下來之後由使用者自己按重新整理，不讓等待的人變成第二波流量。
 */
const PROCESSING_POLL_INTERVAL_MS = 3_000
const PROCESSING_POLL_LIMIT = 20
const polling = ref(false)
let disposed = false

async function pollUntilCreated() {
  if (polling.value) {
    return
  }
  polling.value = true
  for (let round = 0; round < PROCESSING_POLL_LIMIT && !disposed; round++) {
    await new Promise((resolve) => setTimeout(resolve, PROCESSING_POLL_INTERVAL_MS))
    try {
      const latest = await request<OrderView>(`/api/v1/orders/${orderNo}`, { authenticated: true })
      if (!latest.processing) {
        polling.value = false
        await load()
        return
      }
      order.value = latest
    } catch (error) {
      // 建立失敗時後端回「訂單不存在」並帶原因，那是定論，不必再問
      order.value = null
      loadError.value = errorMessage(error, '訂單建立失敗')
      break
    }
  }
  polling.value = false
}

onUnmounted(() => { disposed = true })

const queueHint = computed(() => describeQueue(order.value?.queue))

const reordering = ref(false)
const toast = useToast()

/**
 * 再買一次：把訂單行原樣加回購物車。逐行加而不是一次送整批——
 * 其中一件已下架時，其他件仍然要進得去，並且要說清楚少了哪一件。
 */
async function reorder() {
  if (!order.value) {
    return
  }
  reordering.value = true
  const skipped: string[] = []
  for (const line of order.value.lines) {
    try {
      await cart.addItem(line.skuId, line.quantity)
    } catch {
      skipped.push(line.skuSnapshot)
    }
  }
  reordering.value = false
  if (skipped.length === order.value.lines.length) {
    toast.error('這些商品目前都無法購買')
    return
  }
  // 訊息要活過接下來的換頁，所以不能寫在這一頁的畫面裡
  if (skipped.length > 0) {
    toast.info(`已加入購物車；「${skipped.join('」「')}」目前無法購買，已略過`)
  } else {
    toast.success('已全部加入購物車')
  }
  await navigateTo('/cart')
}

async function pay() {
  paying.value = true
  try {
    // 用共用的 PaymentIntentView 而不是就地寫一個行內型別——
    // 這裡原本寫成 `{ payUrl: string }`，而後端回的是 paymentUrl。
    // 行內型別讓 TypeScript 沒有東西可以比對，於是 undefined 被指派給
    // location.href，瀏覽器把字串 "undefined" 當相對路徑解析，
    // 使用者被導到 /orders/undefined。**付款按鈕是死的，而且沒有任何錯誤**
    const intent = await request<PaymentIntentView>(
      `/api/v1/orders/${orderNo}/payments`,
      { method: 'POST', authenticated: true, body: { method: paymentMethod.value } },
    )
    // 導向模擬金流頁；真實金流同樣是離站，回來時靠回調而非這個導向
    window.location.href = intent.paymentUrl
  } catch (error) {
    loadError.value = errorMessage(error, '無法發起付款')
    paying.value = false
  }
}

/** 期限到了之後等排程關單，再重新載入一次讓狀態跟上。 */
function onPaymentExpired() {
  paymentExpired.value = true
  setTimeout(() => { void load() }, 35_000)
}

async function cancelOrder() {
  cancelling.value = true
  try {
    order.value = await request<OrderView>(
      `/api/v1/orders/${orderNo}/cancel`, { method: 'POST', authenticated: true })
    cancelOpen.value = false
    toast.success('訂單已取消')
  } catch (error) {
    cancelOpen.value = false
    // 最常見的原因是付款在途：後端的訊息已經說明了要等期限自動取消
    toast.error(errorMessage(error, '無法取消訂單'))
  } finally {
    cancelling.value = false
  }
}

onMounted(load)
useHead({ title: `訂單 ${orderNo}` })
</script>

<template>
  <div>
    <!-- 放在 v-if 鏈之外：夾在中間會把後面的 v-else-if 與前面的 v-if 拆開 -->
    <ConfirmDialog
      v-model:open="cancelOpen"
      title="確定要取消這張訂單？"
      confirm-label="取消訂單"
      cancel-label="再想想"
      :busy="cancelling"
      @confirm="cancelOrder"
    >
      取消後無法復原，保留給你的庫存會釋出給其他人。
      <template v-if="order?.channel === 'SECKILL'">
        這是限時搶購的訂單，<b class="text-ink">取消後不保證還搶得到</b>。
      </template>
    </ConfirmDialog>

    <!-- 建立中：庫存已經是他的了，只是訂單還沒落庫。不能畫成一張空訂單 -->
    <template v-if="order?.processing">
      <PageHeader eyebrow="Order" :title="order.orderNo">
        <template #actions>
          <StatusBadge status="PROCESSING" />
        </template>
      </PageHeader>

      <AppCard class="mx-auto max-w-xl px-6 py-12 text-center">
        <div
          class="mx-auto grid h-14 w-14 place-items-center rounded-full bg-accent-soft text-accent"
          aria-hidden="true"
        >
          <span
            v-if="polling"
            class="h-6 w-6 animate-spin rounded-full border-2 border-accent/25 border-t-accent"
          />
          <svg v-else viewBox="0 0 24 24" class="h-7 w-7" fill="none" stroke="currentColor" stroke-width="1.8">
            <circle cx="12" cy="12" r="8" />
            <path d="M12 8v4l2.5 2.5" stroke-linecap="round" />
          </svg>
        </div>
        <h2 class="mt-5 text-lg font-bold" role="status">已搶到，訂單建立中</h2>
        <p class="mx-auto mt-2 max-w-sm text-sm leading-relaxed text-ink-muted">
          庫存已經保留給你，不需要再搶一次。訂單正在排隊寫入，完成後這一頁會自動更新。
        </p>
        <p v-if="queueHint" class="figure mt-4 inline-block rounded-full bg-sunken px-3.5 py-1.5 text-xs text-ink-muted">
          {{ queueHint }}
        </p>
        <div v-if="!polling" class="mt-6 flex flex-col items-center gap-2">
          <p class="text-xs text-ink-faint">等得比預期久，已暫停自動更新。</p>
          <AppButton variant="secondary" size="sm" @click="load">重新整理</AppButton>
        </div>
      </AppCard>
    </template>

    <template v-else-if="order">
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
              <p v-if="order.buyerNote" class="mt-3 border-t border-line pt-3 text-sm">
                <span class="text-ink-faint">備註：</span>{{ order.buyerNote }}
              </p>
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
          <PriceBreakdown
            :subtotal="order.subtotal" :discounts="order.discounts ?? []"
            :payable="order.totalAmount"
            :shipping-fee="order.shippingFee"
            size="xl"
          />

          <template v-if="order.status === 'PENDING_PAYMENT'">
            <PaymentDeadline
              v-if="order.paymentRemainingSeconds != null"
              class="mt-5"
              :remaining-seconds="order.paymentRemainingSeconds"
              :deadline="order.paymentDeadline ?? null"
              @expired="onPaymentExpired"
            />

            <PaymentMethodPicker
              v-model="paymentMethod" class="mt-5" :disabled="paying || paymentExpired"
            />

            <AppButton
              class="mt-5" size="lg" block :disabled="paying || paymentExpired" @click="pay"
            >
              {{ paying ? '前往付款⋯' : '前往付款' }}
            </AppButton>

            <!-- 取消是低調的文字鈕：它不是我們希望使用者做的事，但不想買的人不該只能等它自己過期 -->
            <button
              v-if="!paymentExpired"
              type="button"
              class="mt-3 block w-full text-center text-sm text-ink-muted underline-offset-4
                     transition-colors hover:text-danger hover:underline"
              @click="cancelOpen = true"
            >
              取消訂單
            </button>
          </template>

          <!--
            評價排在退貨前面，而且用 primary。
            對一張已送達的訂單，「分享心得」才是我們希望使用者做的事；
            把它擺在退貨下面等於暗示退貨比較重要
          -->
          <AppButton
            v-if="canReview"
            class="mt-6" size="lg" block
            @click="navigateTo(`/orders/${orderNo}/review`)"
          >
            撰寫評價
          </AppButton>

          <!-- 再買一次只給已付款之後的單：待付款的單本來就還在購物流程裡，
               已關閉的單再買等於重走一次結帳，那正是這顆按鈕要省掉的事 -->
          <AppButton
            v-if="order.status === 'PAID' || order.status === 'SHIPPED' || order.status === 'COMPLETED'"
            class="mt-6" variant="secondary" block :disabled="reordering"
            @click="reorder"
          >
            {{ reordering ? '加入中⋯' : '再買一次' }}
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
