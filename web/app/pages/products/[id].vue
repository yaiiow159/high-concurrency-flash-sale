<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAddresses } from '~/composables/useAddresses'
import { useReviews } from '~/composables/useReviews'
import { useCheckout } from '~/composables/useCheckout'
import { useCartStore } from '~/stores/cart'
import { useAuthStore } from '~/stores/auth'
import type {
  ApiResponse, CategoryView, ProductImageView, ProductPage, ProductRatingView, ProductView,
  SkuStockView, SkuView,
} from '~/types/api'

/**
 * 商品詳情與直接購買。 頁面本身走 ISR（商品資料變動慢），但下單一定是客戶端的動作—— 它帶身分、會改狀態，永遠不該出現在被快取的 HTML 裡。 桌機把購買面板固定在右側，手機改用底部固定操作列—— 主要動作永遠在拇指構得到的地方，而不是跟著內容捲走。
 */
const route = useRoute()
const productId = route.params.id as string

const { data } = await useFetch<ApiResponse<ProductView>>(
  `/api/v1/catalog/products/${productId}`,
)
const product = computed(() => data.value?.data ?? null)

/** 麵包屑與同類商品。 */
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')

/** 從根到目標類目的路徑；找不到時回空陣列。 */
function pathTo(nodes: CategoryView[], target: number): CategoryView[] {
  for (const node of nodes) {
    if (node.categoryId === target) {
      return [node]
    }
    const below = pathTo(node.children ?? [], target)
    if (below.length > 0) {
      return [node, ...below]
    }
  }
  return []
}

const breadcrumb = computed(() => {
  const categoryId = product.value?.categoryId
  return categoryId === undefined || categoryId === null
    ? []
    : pathTo(categoryData.value?.data ?? [], categoryId)
})

/**
 * 商品圖片（ADR-0027）。
 *
 * <b>在 SSR 就取</b>，不是等 onMounted——`og:image` 必須出現在原始 HTML 裡。
 * LINE 與 FB 的爬蟲完全不執行 JS，客戶端才填的話分享出去永遠沒有預覽圖。
 * 圖片本身匿名可讀且變動極慢，併進 SSR 不影響這一頁的快取策略。
 */
const { data: imageData } = await useFetch<ApiResponse<ProductImageView[]>>(
  `/api/v1/catalog/products/${productId}/images`,
  { key: `product-images-${productId}`, default: () => null })

const images = computed<ProductImageView[]>(() => imageData.value?.data ?? [])
const activeImage = ref(0)

const heroImage = computed(() => images.value[activeImage.value]?.url ?? null)

const related = ref<ProductView[]>([])

/**
 * 同類商品。 在客戶端取而不是併進 SSR：這一頁是 ISR 快取的， 而「同類商品」會隨著上下架變動——跟著被快取會顯示已下架的商品。 多要一筆再把自己濾掉：不濾的話推薦區第一個就是使用者正在看的東西。
 */
async function loadRelated() {
  const categoryId = product.value?.categoryId
  if (categoryId === undefined || categoryId === null) {
    return
  }
  try {
    const { request } = useApi()
    const page = await request<ProductPage>(
      `/api/v1/catalog/products?categoryId=${categoryId}&size=9`)
    related.value = page.items
      .filter((item) => String(item.productId) !== productId)
      .slice(0, 8)
  } catch {
    // fail-open：推薦掛掉不該讓人看不到商品本身
    related.value = []
  }
}

onMounted(loadRelated)

/**
 * 看了這個的人也看了。
 *
 * 與「同類商品」是兩件不同的事：同類是目錄結構上的鄰居，
 * 這個是行為上的鄰居——它會推薦出跨類目的搭配（手機殼配手機），
 * 而那是分類推不出來的。
 */
const alsoViewed = ref<ProductView[]>([])

async function loadAlsoViewed() {
  try {
    const { request } = useApi()
    alsoViewed.value = await request<ProductView[]>(
      `/api/v1/catalog/products/${productId}/also-viewed?limit=8`)
  } catch {
    alsoViewed.value = []
  }
}

const auth = useAuthStore()

/** 記一次瀏覽。未登入不記，失敗也不管——它是附加價值。 */
async function recordView() {
  if (!auth.isAuthenticated) {
    return
  }
  try {
    const { request } = useApi()
    await request<void>(`/api/v1/products/${productId}/view`,
      { method: 'POST', authenticated: true })
  } catch {
    // 靜默：瀏覽紀錄記不起來不該讓使用者看到任何東西
  }
}

onMounted(() => {
  void loadAlsoViewed()
  void recordView()
})
const { state, place, reset } = useCheckout()
const cart = useCartStore()

const addingToCart = ref(false)
const cartMessage = ref<string | null>(null)

/** 加入購物車。未登入也能用——內容放在 localStorage，登入後自動併入。 這讓「先逛再登入」成為可能，而不是逼使用者一進站就登入。 */
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

/** 地址在客戶端掛載後才取，絕不進 SSR——這一頁是 ISR 快取的， 個資一旦進了快取的 HTML 就等於發給下一個訪客。 */
const { addresses, defaultAddress, load: loadAddresses } = useAddresses()
const selectedAddressId = ref<number | null>(null)

/**
 * 評價。 在客戶端載入而不是併進這一頁的 SSR：評價變動比商品頻繁得多， 跟著 ISR 一起被快取的話，新評價要等快取過期才看得到。 失敗不擋住商品頁——這是 fail-open，代價只是「少看到評價」， 而使用者仍然買得到東西。
 */
const {
  rating, reviews, loading: reviewsLoading, hasMore: hasMoreReviews,
  load: loadReviews, loadMore: loadMoreReviews,
} = useReviews()

/**
 * 評分聚合另外在 SSR 取一份，只給結構化資料用。
 *
 * 評價清單仍然留在客戶端（它變動快，跟著 ISR 快取會讓新評價看不到），
 * 但 `aggregateRating` 是 rich snippet 的星等來源，不在原始 HTML 裡就等於沒有。
 * 聚合值本來就是快取得起的——它只是一個平均分。
 */
const { data: ratingData } = await useFetch<ApiResponse<ProductRatingView>>(
  `/api/v1/catalog/products/${productId}/rating`,
  { key: `product-rating-${productId}`, default: () => null })

const seoRating = computed(() => rating.value ?? ratingData.value?.data ?? null)

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

/** 預選第一個可購買的規格。 不預選「第一個」而是「第一個可買的」：把使用者放在一個 按下去就會失敗的狀態上，是設計者偷懶而不是使用者的錯。 */
const selectedSkuId = ref<number | null>(null)
watchEffect(() => {
  if (selectedSkuId.value === null && product.value) {
    selectedSkuId.value = product.value.skus.find((sku) => sku.purchasable)?.skuId ?? null
  }
})

/**
 * 庫存。 **另外請求，不併進商品頁的 SSR**——這一頁是 ISR 快取的， 庫存跟著被快取的話會顯示過期的數字，而使用者是照著它決定要不要買。 與評分同一個判斷：變動頻率不同的資料不共用快取。 失敗時整份留空，畫面就不顯示庫存狀態。fail-open： 庫存查詢掛掉不該讓人連商品都看不到。
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

/** 缺貨：庫存查得到而且回報沒貨。查不到就不算——那是「不知道」不是「沒有」。 */
const soldOut = computed(() => selectedStock.value?.inStock === false)

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

const { seo, productJsonLd, breadcrumbJsonLd } = useSeo()

/**
 * SEO。描述沒有商品簡介時退回一句由品名組出來的話——
 * 空的 description 會讓分享卡片只剩一個標題。
 */
watchEffect(() => {
  const current = product.value
  if (!current) {
    return
  }
  seo({
    title: `${current.name}${current.brand ? ` | ${current.brand}` : ''}`,
    description: current.description
      || `${current.name} 現正販售中，多種規格可選，線上下單快速到貨。`,
    image: heroImage.value,
    path: `/products/${current.productId}`,
  })
  productJsonLd(current, seoRating.value, heroImage.value)
  breadcrumbJsonLd([
    { name: '首頁', path: '/' },
    { name: '全部商品', path: '/products' },
    ...breadcrumb.value.map((node) => ({
      name: node.name,
      path: `/products?category=${node.categoryId}`,
    })),
    { name: current.name, path: `/products/${current.productId}` },
  ])
})
</script>

<template>
  <div v-if="product" class="pb-action-bar">
    <nav aria-label="麵包屑" class="text-sm text-ink-muted">
      <ol class="flex flex-wrap items-center gap-1.5">
        <li>
          <NuxtLink to="/" class="transition-colors hover:text-accent">首頁</NuxtLink>
        </li>
        <li class="flex items-center gap-1.5">
          <span aria-hidden="true" class="text-ink-faint">/</span>
          <NuxtLink to="/products" class="transition-colors hover:text-accent">全部商品</NuxtLink>
        </li>
        <li v-for="node in breadcrumb" :key="node.categoryId" class="flex items-center gap-1.5">
          <span aria-hidden="true" class="text-ink-faint">/</span>
          <NuxtLink
            :to="{ path: '/products', query: { category: node.categoryId } }"
            class="transition-colors hover:text-accent"
          >
            {{ node.name }}
          </NuxtLink>
        </li>
      </ol>
    </nav>

    <!-- 主資訊卡：左圖右文，購買動作就在同一張卡裡 -->
    <AppCard class="mt-4 p-4 sm:p-6">
      <div class="grid gap-6 lg:grid-cols-[minmax(0,28rem)_minmax(0,1fr)] lg:gap-10">
        <div class="flex gap-3">
          <!-- 縮圖列直排在左：只有多張圖時才出現 -->
          <ul v-if="images.length > 1" class="hidden w-16 flex-col gap-2 sm:flex">
            <li v-for="(image, index) in images" :key="image.imageId">
              <button
                type="button"
                class="h-16 w-16 overflow-hidden rounded-sm border-2 transition-colors"
                :class="index === activeImage ? 'border-accent' : 'border-line hover:border-line-strong'"
                :aria-label="`看第 ${index + 1} 張圖`"
                :aria-current="index === activeImage"
                @click="activeImage = index"
              >
                <img :src="image.thumbUrl" alt="" loading="lazy" class="h-full w-full object-cover">
              </button>
            </li>
          </ul>
          <div class="min-w-0 flex-1">
            <ProductTile
              :seed="product.productId"
              :label="product.name"
              :src="heroImage"
              class="w-full rounded"
            />
            <ul v-if="images.length > 1" class="scroll-hide mt-3 flex gap-2 overflow-x-auto sm:hidden">
              <li v-for="(image, index) in images" :key="image.imageId" class="shrink-0">
                <button
                  type="button"
                  class="h-14 w-14 overflow-hidden rounded-sm border-2 transition-colors"
                  :class="index === activeImage ? 'border-accent' : 'border-line'"
                  :aria-label="`看第 ${index + 1} 張圖`"
                  @click="activeImage = index"
                >
                  <img :src="image.thumbUrl" alt="" loading="lazy" class="h-full w-full object-cover">
                </button>
              </li>
            </ul>
          </div>
        </div>

        <div class="min-w-0">
          <p v-if="product.brand" class="text-xs font-semibold text-accent">{{ product.brand }}</p>
          <h1 class="mt-1 text-xl font-extrabold leading-snug tracking-tight sm:text-2xl">
            {{ product.name }}
          </h1>
          <!-- 評分緊貼標題，做成連結捲到評價區 -->
          <a
            v-if="rating && rating.count > 0"
            href="#reviews"
            class="mt-2 inline-flex items-center gap-2 text-sm transition-opacity hover:opacity-80"
          >
            <StarRating :value="rating.average" size="sm" />
            <span class="figure text-accent">{{ rating.average.toFixed(1) }}</span>
            <span class="text-ink-muted">{{ rating.count.toLocaleString() }} 則評價</span>
          </a>

          <!-- 價格跟著規格走，不是商品層級的單一數字——SPU/SKU 分離的重點 -->
          <div class="mt-4 rounded bg-danger-soft/70 px-4 py-3.5">
            <p class="eyebrow text-danger/70">售價</p>
            <MoneyText
              :amount="selectedSku?.price ?? product.lowestPrice" size="xl" tone="danger"
              class="mt-0.5"
            />
          </div>

          <section class="mt-5" aria-labelledby="spec-heading">
            <h2 id="spec-heading" class="mb-2 text-sm font-semibold">
              規格
              <span v-if="selectedSku" class="ml-1 font-normal text-ink-muted">
                已選：{{ selectedSku.specDisplay }}
              </span>
            </h2>
            <div class="flex flex-wrap gap-2">
              <button
                v-for="sku in product.skus"
                :key="sku.skuId"
                type="button"
                :disabled="!sku.purchasable"
                :aria-pressed="sku.skuId === selectedSkuId"
                class="h-10 rounded-sm border px-4 text-sm transition-colors
                       disabled:cursor-not-allowed disabled:opacity-40 disabled:line-through"
                :class="sku.skuId === selectedSkuId
                  ? 'border-accent bg-accent-soft font-semibold text-accent'
                  : 'border-line-strong hover:border-accent hover:text-accent'"
                @click="selectedSkuId = sku.skuId"
              >
                {{ sku.specDisplay }}
              </button>
            </div>
          </section>

          <section class="mt-5 flex flex-wrap items-center gap-4">
            <h2 class="text-sm font-semibold">數量</h2>
            <QuantityStepper v-model="quantity" :max="999" />
            <p
              v-if="stockHint"
              class="text-sm"
              :class="stockHint.urgent ? 'font-medium text-accent' : 'text-ink-muted'"
            >
              {{ stockHint.text }}
            </p>
          </section>

          <section v-if="auth.isAuthenticated" class="mt-5" aria-labelledby="address-heading">
            <h2 id="address-heading" class="mb-2 text-sm font-semibold">寄送至</h2>
            <div v-if="addresses.length > 0" class="flex flex-col gap-2">
              <label
                v-for="address in addresses"
                :key="address.addressId"
                class="flex cursor-pointer items-start gap-3 rounded-sm border px-3.5 py-3
                       text-sm transition-colors"
                :class="address.addressId === selectedAddressId
                  ? 'border-accent bg-accent-soft/50'
                  : 'border-line hover:border-line-strong'"
              >
                <input
                  v-model="selectedAddressId"
                  type="radio"
                  name="address"
                  :value="address.addressId"
                  class="mt-1 accent-[var(--accent)]"
                >
                <span class="min-w-0">
                  <span class="font-medium">{{ address.recipientName }}</span>
                  <span class="mt-0.5 block text-ink-muted">{{ address.fullAddress }}</span>
                </span>
              </label>
              <NuxtLink to="/addresses" class="text-sm text-accent hover:underline">
                管理地址 →
              </NuxtLink>
            </div>
            <p v-else class="text-sm text-ink-muted">
              還沒有收貨地址，
              <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
              才能直接購買。
            </p>
          </section>

          <!-- 主要動作。手機由底部操作列接手，這裡只在桌機顯示 -->
          <div class="mt-6 hidden gap-3 lg:flex">
            <!-- 缺貨時把兩顆購買鈕換成「有貨通知我」，留著按不下去的按鈕只是讓人一直去按 -->
            <RestockAlertButton
              v-if="soldOut && selectedSkuId !== null"
              :key="selectedSkuId"
              :sku-id="selectedSkuId"
              class="flex-1"
            />
            <template v-else>
              <AppButton
                variant="outline"
                size="lg"
                class="flex-1"
                :disabled="!selectedSku?.purchasable || addingToCart"
                @click="addToCart"
              >
                {{ addingToCart ? '加入中⋯' : '加入購物車' }}
              </AppButton>
              <AppButton
                v-if="auth.isAuthenticated"
                size="lg" class="flex-1" :disabled="!canBuy" @click="buy"
              >
                {{ submitting ? '處理中⋯' : '立即購買' }}
              </AppButton>
            </template>
          </div>

          <p v-if="!auth.isAuthenticated" class="mt-5 text-sm text-ink-muted">
            可以先加入購物車，登入後會自動併入你的帳號。
          </p>

          <p v-if="cartMessage" class="mt-3 text-sm text-ok" role="status">
            {{ cartMessage }}
            <NuxtLink to="/cart" class="font-medium text-accent hover:underline">查看購物車 →</NuxtLink>
          </p>
          <p
            v-if="state.kind === 'failed'"
            class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
            role="alert"
          >
            {{ state.message }}
          </p>

          <AuthPanel v-if="!auth.isAuthenticated" class="mt-6" />
        </div>
      </div>
    </AppCard>

    <AppCard v-if="product.description" class="mt-5 p-5 sm:p-6">
      <h2 class="section-title mb-4 !text-base">商品說明</h2>
      <p class="max-w-prose whitespace-pre-line leading-relaxed text-ink-muted">
        {{ product.description }}
      </p>
    </AppCard>

    <AppCard id="reviews" class="mt-5 scroll-mt-32 p-5 sm:p-6" aria-labelledby="reviews-heading">
      <h2 id="reviews-heading" class="section-title mb-4 !text-base">商品評價</h2>

      <RatingSummary :rating="rating" :loading="reviewsLoading && reviews.length === 0" />

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
    </AppCard>

    <AppCard class="mt-5 p-5 sm:p-6">
      <ProductQuestions :product-id="Number(productId)" />
    </AppCard>

    <!-- 推薦放在評價之後：使用者看完評價才會決定要不要繼續找 -->
    <ProductRail
      v-if="alsoViewed.length > 0"
      class="mt-10"
      title="看了這個的人也看了"
      :products="alsoViewed"
      :more-to="null"
    />

    <ProductRail
      v-if="related.length > 0"
      class="mt-10"
      title="同類商品"
      :products="related"
      :more-to="product.categoryId === null
        ? { path: '/products' }
        : { path: '/products', query: { category: product.categoryId } }"
    />

    <StickyActionBar>
      <template #info>
        <MoneyText :amount="selectedSku?.price ?? product.lowestPrice" size="lg" tone="danger" />
        <p class="mt-0.5 truncate text-xs text-ink-faint">{{ selectedSku?.specDisplay }}</p>
      </template>
      <template #action>
        <div class="flex gap-2">
          <AppButton
            variant="outline"
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
