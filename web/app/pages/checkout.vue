<script setup lang="ts">
import { useAddresses } from '~/composables/useAddresses'
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'
import type { CheckoutPreview, CouponView, OrderView } from '~/types/api'

/**
 * 結帳頁。
 *
 * **不快取**——購物車與地址都是個資。
 *
 * 品項刻意**不由前端送出**：結帳請求只帶 requestId 與 addressId，
 * 買什麼由伺服器從購物車讀。讓前端送品項等於讓它決定要買什麼，
 * 那樣購物車就只是一個裝飾。
 */
const auth = useAuthStore()
const cart = useCartStore()
const { request } = useApi()
const { addresses, defaultAddress, load: loadAddresses } = useAddresses()

const selectedAddressId = ref<number | null>(null)
const selectedCouponId = ref<number | null>(null)
const submitting = ref(false)
const error = ref<string | null>(null)

const coupons = ref<CouponView[]>([])
const couponsLoading = ref(false)

/**
 * 伺服器算出來的金額明細。
 *
 * 前端**不自己算折扣**：門檻、上限、疊加順序都在後端，
 * 兩邊各算一次遲早會得到不同答案，而使用者只會相信他先看到的那一個。
 *
 * `null` 代表還沒試算完，此時沿用購物車的未折金額顯示——
 * 顯示 0 會讓畫面在載入時閃一下「免費」。
 */
const preview = ref<CheckoutPreview | null>(null)
const payable = computed(() => preview.value?.payable ?? cart.remote?.totalAmount ?? null)
const discounts = computed(() => preview.value?.discounts ?? [])

/**
 * 這一次結帳的冪等鍵。
 *
 * 在進入頁面時就產生並保留，只有成功後才作廢——
 * 失敗重試必須沿用同一個值，因為「失敗」有可能只是回應在路上掉了、
 * 訂單其實已經建立。每次重試都換新值的話，使用者按兩次就會買到兩份。
 */
let requestId: string | null = null

const items = computed(() => cart.remote?.items ?? [])
const canSubmit = computed(
  () => items.value.length > 0
    && selectedAddressId.value !== null
    && !items.value.some((item) => !item.purchasable)
    && !submitting.value,
)

/**
 * 重新試算。
 *
 * 失敗時**清掉明細而不是顯示錯誤**：試算只是預覽，
 * 它掛掉不該擋住使用者結帳——真正的金額本來就是下單當下才決定的。
 * 這是 fail-open，因為這道防線失守的代價只是「少看到折扣」。
 */
/**
 * 合併同一批變動。
 *
 * 進頁面時購物車、優惠券、預設地址是各自 resolve 的，每一份到齊都會觸發
 * 一次試算——實測單次載入打了 **3 次** `/checkout/preview`。
 * 三次的輸入不同（地址還沒設好時算不出運費），所以不是重複請求而是
 * 三次不同的試算，但使用者只看得到最後一次。
 */
const PREVIEW_DEBOUNCE_MS = 100
let previewTimer: ReturnType<typeof setTimeout> | null = null

function schedulePreview() {
  if (previewTimer) {
    clearTimeout(previewTimer)
  }
  previewTimer = setTimeout(() => { void refreshPreview() }, PREVIEW_DEBOUNCE_MS)
}

/**
 * 序號，用來丟掉**過期**的試算結果。
 *
 * 沒有它的話：使用者從本島換到離島，兩次試算同時在路上，
 * 而先發出的本島那份比較慢回來——它會覆蓋掉離島的結果，
 * 畫面顯示 80 元運費而下單當下收 200 元。
 * 這與去抖是兩件事，去抖減少請求，序號保證顯示的是最後一次的答案。
 */
let previewSeq = 0

async function refreshPreview() {
  const seq = ++previewSeq
  if (!auth.isAuthenticated || items.value.length === 0) {
    preview.value = null
    return
  }
  try {
    const result = await request<CheckoutPreview>('/api/v1/orders/checkout/preview', {
      method: 'POST',
      authenticated: true,
      body: { couponId: selectedCouponId.value, addressId: selectedAddressId.value },
    })
    if (seq === previewSeq) {
      preview.value = result
    }
  } catch {
    if (seq === previewSeq) {
      preview.value = null
    }
  }
}

async function loadCoupons() {
  couponsLoading.value = true
  try {
    coupons.value = await request<CouponView[]>('/api/v1/coupons', { authenticated: true })
  } catch {
    coupons.value = []
  } finally {
    couponsLoading.value = false
  }
}

async function submit() {
  if (!canSubmit.value || selectedAddressId.value === null) {
    return
  }
  requestId ??= crypto.randomUUID()
  submitting.value = true
  error.value = null

  try {
    const order = await request<OrderView>('/api/v1/orders/checkout', {
      method: 'POST',
      authenticated: true,
      body: {
        requestId,
        addressId: selectedAddressId.value,
        couponId: selectedCouponId.value,
      },
    })
    requestId = null
    cart.reset()
    await navigateTo(`/orders/${order.orderNo}`)
  } catch (cause) {
    error.value = errorMessage(cause, '結帳失敗，請稍後再試')
  } finally {
    submitting.value = false
  }
}

/** 結帳需要的三份資料：購物車、地址簿、可用的券。 */
async function loadCheckoutData() {
  if (!auth.isAuthenticated) {
    return
  }
  await Promise.all([cart.load(), loadAddresses(), loadCoupons()])
  // 排程而不是直接呼叫——三份資料到齊會各自觸發 watch，
  // 讓它們與這一次合併成同一次試算
  schedulePreview()
}

// onMounted 只在客戶端跑，這是刻意的：這幾份都是個資，
// 在伺服器端預先取會讓已登入與未登入渲染出不同的 HTML，接手時就是 hydration mismatch
onMounted(loadCheckoutData)

// 未登入時這一頁會**就地**顯示登入面板，登入成功後不換頁，
// onMounted 因此不會再跑一次。少了這個 watch，使用者登入完會看到
// 「還沒有收貨地址」與「沒有可用的優惠券」——兩者都不是真的
watch(() => auth.isAuthenticated, (authenticated) => {
  if (authenticated) {
    void loadCheckoutData()
  }
})

// 換券或購物車內容變動都要重算。監看品項的簽章而不是整個陣列，
// 是為了避免購物車重新載入（內容相同但物件不同）時多打一次試算
// 換券、換地址、或購物車內容變動都要重算。
// **地址必須在裡面**——離島運費是本島的兩三倍，換了地址卻沿用舊運費，
// 使用者會在下一步被收一個他沒看過的金額
watch(
  [
    selectedCouponId,
    selectedAddressId,
    () => items.value.map((item) => `${item.skuId}x${item.quantity}`).join(','),
  ],
  schedulePreview,
)
watchEffect(() => {
  if (selectedAddressId.value === null && defaultAddress.value) {
    selectedAddressId.value = defaultAddress.value.addressId
  }
})

useHead({ title: '結帳' })
</script>

<template>
  <div :class="items.length > 0 ? 'pb-action-bar' : ''">
    <PageHeader eyebrow="Checkout" title="結帳">
      <template #actions>
        <NuxtLink to="/cart" class="text-sm text-ink-muted transition-colors hover:text-ink">
          ← 回購物車
        </NuxtLink>
      </template>
    </PageHeader>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div
      v-else-if="items.length > 0"
      class="grid gap-8 lg:grid-cols-[1fr_20rem] lg:items-start"
    >
      <div class="flex flex-col gap-8">
        <section aria-labelledby="items-heading">
          <h2 id="items-heading" class="eyebrow mb-3">訂單內容</h2>
          <ul class="flex flex-col gap-2">
            <li v-for="item in items" :key="item.skuId">
              <AppCard class="flex flex-wrap items-baseline justify-between gap-4 p-4">
                <div>
                  <p class="font-medium">{{ item.productName }}</p>
                  <p class="mt-1 text-sm text-ink-muted">
                    {{ item.specDisplay }}
                    <span v-if="!item.purchasable" class="text-danger">（已下架）</span>
                  </p>
                </div>
                <p class="flex items-baseline gap-1.5 text-sm text-ink-muted">
                  <MoneyText :amount="item.unitPrice" size="sm" tone="muted" />
                  <span class="figure">× {{ item.quantity }}</span>
                </p>
              </AppCard>
            </li>
          </ul>
        </section>

        <CouponPicker
          v-model="selectedCouponId" :coupons="coupons" :loading="couponsLoading"
        />

        <section aria-labelledby="address-heading">
          <h2 id="address-heading" class="eyebrow mb-3">寄送至</h2>
          <div v-if="addresses.length > 0" class="flex flex-col gap-2">
            <label
              v-for="address in addresses"
              :key="address.addressId"
              class="flex cursor-pointer items-start gap-3 rounded-sm border p-4
                     text-sm transition-colors"
              :class="address.addressId === selectedAddressId
                ? 'border-accent bg-accent-soft'
                : 'border-line hover:border-line-strong'"
            >
              <input
                v-model="selectedAddressId" type="radio" name="address"
                :value="address.addressId" class="mt-1 accent-[var(--accent)]"
              >
              <span>
                <span class="font-medium">{{ address.recipientName }}</span>
                <span class="figure ml-2 text-xs text-ink-faint">{{ address.phone }}</span>
                <span class="mt-1 block text-ink-muted">{{ address.fullAddress }}</span>
              </span>
            </label>
          </div>
          <p v-else class="text-sm text-ink-muted">
            還沒有收貨地址，
            <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
            才能結帳。
          </p>
        </section>
      </div>

      <AppCard class="hidden p-5 lg:sticky lg:top-24 lg:block">
        <h2 class="eyebrow mb-4">應付金額</h2>
        <PriceBreakdown
          :subtotal="preview?.subtotal ?? cart.remote?.totalAmount"
          :discounts="discounts" :payable="payable"
          :shipping-fee="preview?.shippingFee"
          :shipping-known="preview?.shippingKnown ?? false"
          :shipping-zone="preview?.shippingZone"
          size="xl"
        />

        <AppButton class="mt-6" size="lg" block :disabled="!canSubmit" @click="submit">
          {{ submitting ? '處理中⋯' : '確認下單' }}
        </AppButton>

        <p
          v-if="error"
          class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
          role="alert"
        >
          {{ error }}
        </p>
      </AppCard>
    </div>

    <EmptyState v-else title="購物車是空的。" hint="加點東西再回來結帳。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        去逛商品
      </AppButton>
    </EmptyState>

    <StickyActionBar v-if="auth.isAuthenticated && items.length > 0">
      <template #info>
        <MoneyText :amount="preview?.shippingKnown ? preview.total : payable" size="lg" />
        <p v-if="discounts.length > 0" class="mt-0.5 truncate text-xs text-accent">
          已折 {{ preview?.totalDiscount?.toLocaleString() }}
        </p>
        <p v-else-if="!preview?.shippingKnown" class="mt-0.5 truncate text-xs text-ink-faint">
          未含運費
        </p>
        <p v-if="error" class="mt-0.5 truncate text-xs text-danger">{{ error }}</p>
      </template>
      <template #action>
        <AppButton :disabled="!canSubmit" @click="submit">
          {{ submitting ? '處理中⋯' : '確認下單' }}
        </AppButton>
      </template>
    </StickyActionBar>
  </div>
</template>
