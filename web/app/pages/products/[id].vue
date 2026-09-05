<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAddresses } from '~/composables/useAddresses'
import { useReviews } from '~/composables/useReviews'
import { useCheckout } from '~/composables/useCheckout'
import { useCartStore } from '~/stores/cart'
import { useAuthStore } from '~/stores/auth'
import type { ApiResponse, ProductView, SkuStockView, SkuView } from '~/types/api'

/**
 * 商品詳情與直接購買。
 *
 * 頁面本身走 ISR（商品資料變動慢），但下單一定是客戶端的動作——
 * 它帶身分、會改狀態，永遠不該出現在被快取的 HTML 裡。
 *
 * 桌機把購買面板固定在右側，手機改用底部固定操作列——
 * 主要動作永遠在拇指構得到的地方，而不是跟著內容捲走。
 */
const route = useRoute()
const productId = route.params.id as string

const { data } = await useFetch<ApiResponse<ProductView>>(
  `/api/v1/catalog/products/${productId}`,
)
const product = computed(() => data.value?.data ?? null)

const auth = useAuthStore()
const { state, place, reset } = useCheckout()
const cart = useCartStore()

const addingToCart = ref(false)
const cartMessage = ref<string | null>(null)

/**
 * 加入購物車。未登入也能用——內容放在 localStorage，登入後自動併入。
 * 這讓「先逛再登入」成為可能，而不是逼使用者一進站就登入。
 */
async function addToCart() {
  if (!selectedSku.value) {
    return
  }
  addingToCart.value = true
  cartMessage.value = null
  try {
    await cart.addItem(selectedSku.value.skuId, quantity.value)
    cartMessage.value = '已加入購物車'
  } catch (cause) {
    cartMessage.value = errorMessage(cause, '加入購物車失敗')
  } finally {
    addingToCart.value = false
  }
}

/**
 * 地址在客戶端掛載後才取，絕不進 SSR——這一頁是 ISR 快取的，
 * 個資一旦進了快取的 HTML 就等於發給下一個訪客。
 */
const { addresses, defaultAddress, load: loadAddresses } = useAddresses()
const selectedAddressId = ref<number | null>(null)

/**
 * 評價。
 *
 * 在客戶端載入而不是併進這一頁的 SSR：評價變動比商品頻繁得多，
 * 跟著 ISR 一起被快取的話，新評價要等快取過期才看得到。
 *
 * 失敗不擋住商品頁——這是 fail-open，代價只是「少看到評價」，
 * 而使用者仍然買得到東西。
 */
const {
  rating, reviews, loading: reviewsLoading, hasMore: hasMoreReviews,
  load: loadReviews, loadMore: loadMoreReviews,
} = useReviews()

onMounted(() => {
  void loadReviews(productId)
  if (auth.isAuthenticated) {
    loadAddresses()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    loadAddresses()
  }
})
// 預選預設地址，讓多數人不必多按一次
watchEffect(() => {
  if (selectedAddressId.value === null && defaultAddress.value) {
    selectedAddressId.value = defaultAddress.value.addressId
  }
})

/**
 * 預選第一個可購買的規格。
 *
 * 不預選「第一個」而是「第一個可買的」：把使用者放在一個
 * 按下去就會失敗的狀態上，是設計者偷懶而不是使用者的錯。
 */
const selectedSkuId = ref<number | null>(null)
watchEffect(() => {
  if (selectedSkuId.value === null && product.value) {
    selectedSkuId.value = product.value.skus.find((sku) => sku.purchasable)?.skuId ?? null
  }
})

/**
 * 庫存。
 *
 * **另外請求，不併進商品頁的 SSR**——這一頁是 ISR 快取的，
 * 庫存跟著被快取的話會顯示過期的數字，而使用者是照著它決定要不要買。
 * 與評分同一個判斷：變動頻率不同的資料不共用快取。
 *
 * 失敗時整份留空，畫面就不顯示庫存狀態。fail-open：
 * 庫存查詢掛掉不該讓人連商品都看不到。
 */
const stock = ref<Record<number, SkuStockView>>({})

async function loadStock() {
  const ids = product.value?.skus.map((sku) => sku.skuId) ?? []
  if (ids.length === 0) {
    return
  }
  try {
    const { request } = useApi()
    const rows = await request<SkuStockView[]>(
      `/api/v1/catalog/stock?skuIds=${ids.join(',')}`)
    stock.value = Object.fromEntries(rows.map((row) => [row.skuId, row]))
  } catch {
    stock.value = {}
  }
}

onMounted(loadStock)

const selectedStock = computed(() =>
  selectedSkuId.value === null ? null : stock.value[selectedSkuId.value] ?? null)

/** 庫存提示。查不到就不顯示，而不是顯示「缺貨」——那兩件事不一樣。 */
const stockHint = computed(() => {
  const current = selectedStock.value
  if (!current) {
    return null
  }
  if (!current.inStock) {
    return { text: '已售完', urgent: true }
  }
  return current.lowStock && current.available !== null
    ? { text: `僅剩 ${current.available} 件`, urgent: true }
    : { text: '有現貨', urgent: false }
})

const selectedSku = computed<SkuView | null>(
  () => product.value?.skus.find((sku) => sku.skuId === selectedSkuId.value) ?? null,
)

const quantity = ref(1)
const submitting = computed(() => state.value.kind === 'submitting')
const canBuy = computed(
  () => auth.isAuthenticated
    && selectedSku.value?.purchasable === true
    && selectedAddressId.value !== null
    && !submitting.value,
)

async function buy() {
  if (!selectedSku.value || selectedAddressId.value === null) {
    return
  }
  await place(
    [{ skuId: selectedSku.value.skuId, quantity: quantity.value }],
    selectedAddressId.value,
  )
  if (state.value.kind === 'placed') {
    await navigateTo(`/orders/${state.value.order.orderNo}`)
  }
}

// 換規格後先前的訊息就不再適用，留著只會誤導
watch(selectedSkuId, () => {
  cartMessage.value = null
  if (state.value.kind === 'failed') {
    reset()
  }
})

useHead(() => ({ title: product.value?.name ?? '商品' }))
</script>

<template>
  <div v-if="product" class="pb-action-bar">
    <NuxtLink
      to="/products"
      class="inline-block text-sm text-ink-muted transition-colors hover:text-ink"
    >
      ← 全部商品
    </NuxtLink>

    <div class="mt-5 grid gap-8 lg:grid-cols-[minmax(0,1fr)_22rem] lg:items-start lg:gap-12">
      <!-- 左欄：商品視覺與規格 -->
      <div>
        <ProductTile
          :seed="product.productId"
          :label="product.name"
          ratio="wide"
          class="w-full rounded"
        />

        <div class="mt-6">
          <p v-if="product.brand" class="eyebrow">{{ product.brand }}</p>
          <h1 class="mt-1.5 text-2xl font-bold tracking-tight sm:text-3xl">
            {{ product.name }}
          </h1>
          <!--
            緊湊評分緊貼標題，那是電商商品頁的固定位置。
            做成連結捲到評價區：使用者看到 4.3 分的下一個動作
            就是想知道那 4.3 分是怎麼來的
          -->
          <a
            v-if="rating && rating.count > 0"
            href="#reviews"
            class="mt-2.5 inline-flex items-center gap-2 text-sm transition-opacity hover:opacity-80"
          >
            <StarRating :value="rating.average" size="sm" />
            <span class="figure font-medium">{{ rating.average.toFixed(1) }}</span>
            <span class="text-ink-muted underline decoration-line underline-offset-4">
              {{ rating.count.toLocaleString() }} 則評價
            </span>
          </a>

          <p v-if="product.description" class="mt-3 max-w-prose text-ink-muted">
            {{ product.description }}
          </p>
        </div>

        <!-- 價格跟著規格走，不是商品層級的單一數字——SPU/SKU 分離的重點 -->
        <div class="mt-6">
          <MoneyText :amount="selectedSku?.price ?? product.lowestPrice" size="xl" />
        </div>

        <section class="mt-8" aria-labelledby="spec-heading">
          <h2 id="spec-heading" class="eyebrow mb-3">選擇規格</h2>
          <div class="flex flex-wrap gap-2">
            <button
              v-for="sku in product.skus"
              :key="sku.skuId"
              type="button"
              :disabled="!sku.purchasable"
              :aria-pressed="sku.skuId === selectedSkuId"
              class="h-11 rounded-sm border px-4 text-sm transition-colors
                     disabled:cursor-not-allowed disabled:opacity-40"
              :class="sku.skuId === selectedSkuId
                ? 'border-cta bg-accent-soft font-medium text-accent'
                : 'border-line hover:border-line-strong'"
              @click="selectedSkuId = sku.skuId"
            >
              {{ sku.specDisplay }}
            </button>
          </div>
        </section>

        <section class="mt-6 flex items-center gap-3">
          <label for="quantity" class="eyebrow">數量</label>
          <input
            id="quantity"
            v-model.number="quantity"
            type="number"
            min="1"
            max="999"
            class="figure h-11 w-20 rounded-sm border border-line bg-surface px-3 text-center"
          >
        </section>

        <section id="reviews" class="mt-12 scroll-mt-24" aria-labelledby="reviews-heading">
          <h2 id="reviews-heading" class="eyebrow mb-4">商品評價</h2>

          <RatingSummary :rating="rating" :loading="reviewsLoading && reviews.length === 0" />

          <!-- divide-y 而不是各自加邊框：最後一則不該有底線 -->
          <div v-if="reviews.length > 0" class="mt-2 divide-y divide-line">
            <ReviewCard v-for="review in reviews" :key="review.reviewId" :review="review" />
          </div>

          <div v-if="hasMoreReviews" class="mt-5 flex justify-center">
            <AppButton
              variant="secondary" size="sm" :disabled="reviewsLoading"
              @click="loadMoreReviews(productId)"
            >
              {{ reviewsLoading ? '載入中⋯' : '看更多評價' }}
            </AppButton>
          </div>
        </section>
      </div>

      <!-- 右欄：購買面板。桌機固定在側，手機改用底部操作列 -->
      <AppCard class="hidden p-5 lg:sticky lg:top-24 lg:block">
        <template v-if="auth.isAuthenticated">
          <section aria-labelledby="address-heading">
            <h2 id="address-heading" class="eyebrow mb-3">寄送至</h2>

            <div v-if="addresses.length > 0" class="flex flex-col gap-2">
              <label
                v-for="address in addresses"
                :key="address.addressId"
                class="flex cursor-pointer items-start gap-3 rounded-sm border p-3.5
                       text-sm transition-colors"
                :class="address.addressId === selectedAddressId
                  ? 'border-cta bg-accent-soft'
                  : 'border-line hover:border-line-strong'"
              >
                <input
                  v-model="selectedAddressId"
                  type="radio"
                  name="address"
                  :value="address.addressId"
                  class="mt-1 accent-[var(--cta)]"
                >
                <span>
                  <span class="font-medium">{{ address.recipientName }}</span>
                  <span class="mt-1 block text-ink-muted">{{ address.fullAddress }}</span>
                </span>
              </label>
              <NuxtLink to="/addresses" class="mt-1 text-sm text-accent hover:underline">
                管理地址 →
              </NuxtLink>
            </div>

            <p v-else class="text-sm text-ink-muted">
              還沒有收貨地址，
              <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
              才能直接購買。
            </p>
          </section>

          <p
            v-if="stockHint"
            class="mt-5 text-sm"
            :class="stockHint.urgent ? 'text-accent' : 'text-ink-muted'"
          >
            {{ stockHint.text }}
          </p>

          <div class="mt-6 flex flex-col gap-2.5">
            <AppButton
              variant="secondary"
              size="lg"
              block
              :disabled="!selectedSku?.purchasable || addingToCart"
              @click="addToCart"
            >
              {{ addingToCart ? '加入中⋯' : '加入購物車' }}
            </AppButton>
            <AppButton size="lg" block :disabled="!canBuy" @click="buy">
              {{ submitting ? '處理中⋯' : '立即購買' }}
            </AppButton>
          </div>
        </template>

        <template v-else>
          <p class="text-sm text-ink-muted">
            可以先加入購物車，登入後會自動併入你的帳號。
          </p>
          <AppButton
            class="mt-4"
            variant="secondary"
            size="lg"
            block
            :disabled="!selectedSku?.purchasable || addingToCart"
            @click="addToCart"
          >
            {{ addingToCart ? '加入中⋯' : '加入購物車' }}
          </AppButton>
          <AuthPanel class="mt-6" />
        </template>

        <p v-if="cartMessage" class="mt-3 text-sm text-ink-muted" role="status">
          {{ cartMessage }}
          <NuxtLink to="/cart" class="text-accent hover:underline">查看購物車 →</NuxtLink>
        </p>

        <p
          v-if="state.kind === 'failed'"
          class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
          role="alert"
        >
          {{ state.message }}
        </p>
      </AppCard>
    </div>

    <!-- 手機：地址與登入留在內容流裡，主要動作交給底部操作列 -->
    <div class="mt-8 lg:hidden">
      <template v-if="auth.isAuthenticated">
        <h2 class="eyebrow mb-3">寄送至</h2>
        <div v-if="addresses.length > 0" class="flex flex-col gap-2">
          <label
            v-for="address in addresses"
            :key="address.addressId"
            class="flex cursor-pointer items-start gap-3 rounded-sm border p-3.5
                   text-sm transition-colors"
            :class="address.addressId === selectedAddressId
              ? 'border-cta bg-accent-soft'
              : 'border-line'"
          >
            <input
              v-model="selectedAddressId"
              type="radio"
              name="address-mobile"
              :value="address.addressId"
              class="mt-1 accent-[var(--cta)]"
            >
            <span>
              <span class="font-medium">{{ address.recipientName }}</span>
              <span class="mt-1 block text-ink-muted">{{ address.fullAddress }}</span>
            </span>
          </label>
        </div>
        <p v-else class="text-sm text-ink-muted">
          還沒有收貨地址，
          <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
          才能直接購買。
        </p>
      </template>
      <AuthPanel v-else />

      <p v-if="cartMessage" class="mt-3 text-sm text-ink-muted" role="status">
        {{ cartMessage }}
        <NuxtLink to="/cart" class="text-accent hover:underline">查看購物車 →</NuxtLink>
      </p>
      <p
        v-if="state.kind === 'failed'"
        class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
        role="alert"
      >
        {{ state.message }}
      </p>
    </div>

    <StickyActionBar>
      <template #info>
        <MoneyText :amount="selectedSku?.price ?? product.lowestPrice" size="lg" />
        <p class="mt-0.5 truncate text-xs text-ink-faint">{{ selectedSku?.specDisplay }}</p>
      </template>
      <template #action>
        <div class="flex gap-2">
          <AppButton
            variant="secondary"
            :disabled="!selectedSku?.purchasable || addingToCart"
            @click="addToCart"
          >
            加入購物車
          </AppButton>
          <AppButton v-if="auth.isAuthenticated" :disabled="!canBuy" @click="buy">
            {{ submitting ? '處理中⋯' : '購買' }}
          </AppButton>
        </div>
      </template>
    </StickyActionBar>
  </div>

  <EmptyState v-else title="找不到這個商品。">
    <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
      回商品列表
    </AppButton>
  </EmptyState>
</template>
