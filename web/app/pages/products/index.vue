<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import type {
  ApiResponse, CategoryView, ProductImageView, ProductPage, ProductRatingView, ProductView, SkuStockView,
} from '~/types/api'

/**
 * 商品列表。
 *
 * ISR 快取 5 分鐘。這一頁能被 CDN 快取的前提是**回應不含庫存與身分**：
 * 商品描述幾週才改一次，庫存每秒變動數千次，混在一起整頁就失去快取價值。
 * 目錄端點也全部開放匿名——帶 Authorization 的請求無法共用快取。
 */
const route = useRoute()
const categoryId = computed(() => {
  const raw = route.query.category
  return typeof raw === 'string' ? Number(raw) : null
})

/** 排序方式。放在網址上，讓「依價格排序的第 3 頁」這種連結貼得出去。 */
const SORT_OPTIONS = [
  { value: 'NEWEST', label: '最新上架' },
  { value: 'BEST_SELLING', label: '熱賣' },
  { value: 'RATING', label: '評分' },
  { value: 'PRICE_ASC', label: '價格低到高' },
  { value: 'PRICE_DESC', label: '價格高到低' },
] as const

const sort = computed(() => {
  const raw = route.query.sort
  const value = typeof raw === 'string' ? raw.toUpperCase() : 'NEWEST'
  return SORT_OPTIONS.some((option) => option.value === value) ? value : 'NEWEST'
})

/** 價格區間。放在網址上，讓「1000 以下的耳機」這種連結貼得出去。 */
const priceQuery = computed(() => {
  const read = (key: string) => {
    const raw = route.query[key]
    const value = typeof raw === 'string' ? Number(raw) : Number.NaN
    return Number.isFinite(value) && value >= 0 ? value : null
  }
  return { min: read('minPrice'), max: read('maxPrice') }
})

const minInput = ref<string>('')
const maxInput = ref<string>('')

watchEffect(() => {
  minInput.value = priceQuery.value.min === null ? '' : String(priceQuery.value.min)
  maxInput.value = priceQuery.value.max === null ? '' : String(priceQuery.value.max)
})

function applyPrice() {
  const query: Record<string, string> = {}
  if (categoryId.value !== null) {
    query.category = String(categoryId.value)
  }
  if (sort.value !== 'NEWEST') {
    query.sort = sort.value
  }
  if (minInput.value.trim() !== '') {
    query.minPrice = minInput.value.trim()
  }
  if (maxInput.value.trim() !== '') {
    query.maxPrice = maxInput.value.trim()
  }
  void navigateTo({ path: '/products', query })
}

function clearPrice() {
  minInput.value = ''
  maxInput.value = ''
  applyPrice()
}

const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories',
)
const { data: productData } = await useFetch<ApiResponse<ProductPage>>(
  '/api/v1/catalog/products',
  // key 帶上類目，否則切換類目時 Nuxt 會沿用同一份快取結果
  {
    query: {
      categoryId,
      sort,
      minPrice: computed(() => priceQuery.value.min),
      maxPrice: computed(() => priceQuery.value.max),
    },
    // key 要帶上類目**與排序**，否則換排序時 Nuxt 會沿用同一份快取結果
    // key 要帶上每一個會改變結果的條件，否則換條件時 Nuxt 會沿用舊快取
    key: () => `products-${categoryId.value ?? 'all'}-${sort.value}`
      + `-${priceQuery.value.min ?? ''}-${priceQuery.value.max ?? ''}`,
  },
)

const categories = computed(() => categoryData.value?.data ?? [])

/**
 * 已載入的商品。
 *
 * 第一頁來自 SSR（可被 ISR 快取），後續由「載入更多」在客戶端接上去。
 * **不把後續頁併進 SSR**——那會讓捲到第幾頁變成快取鍵的一部分，
 * 命中率立刻掉到零。
 */
const loadedMore = ref<ProductView[]>([])
const products = computed(() => [
  ...(productData.value?.data?.items ?? []),
  ...loadedMore.value,
])

const cursor = ref<string | null>(null)
const hasMore = ref(false)
const loadingMore = ref(false)

// 換類目時已載入的第二頁以後全部作廢——它們屬於上一個類目
watch([categoryId, sort, priceQuery], () => {
  loadedMore.value = []
  cursor.value = productData.value?.data?.nextCursor ?? null
  hasMore.value = productData.value?.data?.hasMore ?? false
})
watchEffect(() => {
  if (loadedMore.value.length === 0) {
    cursor.value = productData.value?.data?.nextCursor ?? null
    hasMore.value = productData.value?.data?.hasMore ?? false
  }
})

async function loadMore() {
  if (loadingMore.value || !hasMore.value || cursor.value === null) {
    return
  }
  loadingMore.value = true
  try {
    const { request } = useApi()
    const query = new URLSearchParams({ cursor: cursor.value, sort: sort.value })
    if (categoryId.value !== null) {
      query.set('categoryId', String(categoryId.value))
    }
    if (priceQuery.value.min !== null) {
      query.set('minPrice', String(priceQuery.value.min))
    }
    if (priceQuery.value.max !== null) {
      query.set('maxPrice', String(priceQuery.value.max))
    }
    const page = await request<ProductPage>(`/api/v1/catalog/products?${query}`)
    loadedMore.value = [...loadedMore.value, ...page.items]
    // 游標原樣沿用伺服器給的值，不從 items 自己取最後一筆的 id
    cursor.value = page.nextCursor
    hasMore.value = page.hasMore
  } catch {
    // 載入更多失敗不清掉已經看到的商品——使用者按一次沒反應，
    // 比整頁商品憑空消失好得多
    hasMore.value = false
  } finally {
    loadingMore.value = false
  }
}

type CategoryOption = { id: number, label: string, depth: number }

/** 從根到指定類目的路徑；找不到時回空陣列。 */
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

/**
 * 逐層展開，不是一次攤平整棵樹。
 *
 * 先前只攤平兩層，商品卻掛在第三層，於是 225 個類目只有 15 個點得到——
 * 而那 15 個又因為篩選不含子樹全部回空頁面（ADR-0022）。
 *
 * **但「完整攤平」是另一個極端**：225 個類目一次全列出來，
 * 實測會把商品格擠出整個畫面，使用者要捲過一整頁的類目才看得到第一件商品。
 * 攤平修好了「點不到」，卻換來「找不到」。
 *
 * 因此只顯示：根類目 + 目前選取路徑上每一層的子類目。
 * 沒選時只有根，逐層點下去逐層展開，畫面上最多幾十個而不是 225 個。
 * 選取狀態本身就在網址上，不需要額外的展開狀態。
 */
const categoryOptions = computed<CategoryOption[]>(() => {
  const roots = categories.value
  const path = categoryId.value === null ? [] : pathTo(roots, categoryId.value)

  const options: CategoryOption[] = roots.map((node) => ({
    id: node.categoryId, label: node.name, depth: 0,
  }))

  path.forEach((node, index) => {
    (node.children ?? []).forEach((child) => {
      options.push({ id: child.categoryId, label: child.name, depth: index + 1 })
    })
  })
  return options
})

const activeCategoryName = computed(() =>
  categoryOptions.value.find((option) => option.id === categoryId.value)?.label ?? null,
)

/**
 * 評分。
 *
 * **一次批次取回整頁的商品，不是每張卡各打一次**——一頁 24 件商品
 * 逐件查就是 24 次往返，而那正是 N+1 在前端的樣子。
 *
 * 在客戶端載入而不是併進這一頁的 SSR：這一頁是 ISR 快取 5 分鐘的，
 * 評分跟著一起被快取的話，新評價要等快取過期才看得到。
 * 這與「庫存不進這一頁」是同一個判斷——變動頻率不同的資料不該共用快取。
 *
 * 失敗時整份留空，卡片就不顯示星等。fail-open：評分掛掉不該讓人逛不了商品。
 */
const ratings = ref<Record<number, ProductRatingView>>({})
/** 主圖。與評分同一個做法：客戶端批次取，不併進 ISR 快取的 SSR。 */
const images = ref<Record<number, ProductImageView>>({})

async function loadRatings() {
  const ids = products.value.map((product) => product.productId)
  if (ids.length === 0) {
    return
  }
  try {
    const { request } = useApi()
    const query = ids.join(',')
    // 兩支一起發，不要一支等一支——它們互不相依
    const [rating, image] = await Promise.all([
      request<Record<number, ProductRatingView>>(
        `/api/v1/catalog/products/ratings?productIds=${query}`),
      request<Record<number, ProductImageView>>(
        `/api/v1/catalog/products/images?productIds=${query}`),
    ])
    ratings.value = rating
    images.value = image
  } catch {
    ratings.value = {}
    images.value = {}
  }
}

onMounted(loadRatings)
// 切換類目時商品換了一批，星等要跟著重取
watch(products, () => { void loadRatings() })

useHead({ title: '全部商品' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Catalog"
      title="全部商品"
      :description="activeCategoryName
        ? `目前顯示「${activeCategoryName}」類目下的商品`
        : '價格掛在規格上——同一個商品的不同規格各有各的價格'"
    />

    <nav class="mb-8 flex flex-wrap gap-2" aria-label="類目篩選">
      <NuxtLink
        to="/products"
        class="rounded-full border px-3.5 py-1.5 text-sm transition-colors"
        :class="categoryId === null
          ? 'border-accent bg-accent-soft font-medium text-accent'
          : 'border-line bg-surface text-ink-muted hover:border-line-strong hover:text-ink'"
      >
        全部
      </NuxtLink>
      <NuxtLink
        v-for="option in categoryOptions"
        :key="option.id"
        :to="{ path: '/products', query: { category: option.id } }"
        class="rounded-full border px-3.5 py-1.5 text-sm transition-colors"
        :class="[
          categoryId === option.id
            ? 'border-accent bg-accent-soft font-medium text-accent'
            : 'border-line bg-surface text-ink-muted hover:border-line-strong hover:text-ink',
        ]"
        :style="option.depth > 0 ? { marginLeft: `${option.depth * 10}px` } : undefined"
      >
        <span v-if="option.depth > 0" class="mr-1 text-ink-faint">└</span>{{ option.label }}
      </NuxtLink>
    </nav>

    <!--
      排序做成分段控制項的樣子，而不是一排文字連結。

      先前是純文字，混在標題與商品格之間讀起來像說明文字而不是可以按的東西。
      仍然用 <a>：每一種排序都要是可以貼出去的網址。
    -->
    <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
      <div class="inline-flex rounded-sm border border-line bg-surface p-0.5 shadow-rest">
        <NuxtLink
          v-for="option in SORT_OPTIONS"
          :key="option.value"
          :to="{ path: '/products', query: {
            ...(categoryId === null ? {} : { category: categoryId }),
            ...(option.value === 'NEWEST' ? {} : { sort: option.value }),
          } }"
          class="rounded-sm px-3 py-1.5 text-xs font-medium transition-colors"
          :class="sort === option.value
            ? 'bg-accent text-on-accent'
            : 'text-ink-muted hover:bg-sunken hover:text-ink'"
        >
          {{ option.label }}
        </NuxtLink>
      </div>
      <div class="flex items-center gap-2">
        <!--
          價格區間。上下限顛倒時後端會自動對調而不是報錯——
          把 1000 打在「最低」是很常見的手滑，而回一個「參數錯誤」
          只會讓人盯著兩個看起來都沒問題的數字
        -->
        <label class="sr-only" for="min-price">最低價</label>
        <input
          id="min-price" v-model="minInput" type="number" min="0" inputmode="numeric"
          placeholder="最低" class="figure w-20 rounded-sm border border-line bg-surface
                 px-2 py-1.5 text-xs shadow-rest placeholder:text-ink-faint"
          @keyup.enter="applyPrice"
        >
        <span class="text-xs text-ink-faint">–</span>
        <label class="sr-only" for="max-price">最高價</label>
        <input
          id="max-price" v-model="maxInput" type="number" min="0" inputmode="numeric"
          placeholder="最高" class="figure w-20 rounded-sm border border-line bg-surface
                 px-2 py-1.5 text-xs shadow-rest placeholder:text-ink-faint"
          @keyup.enter="applyPrice"
        >
        <AppButton variant="secondary" size="sm" @click="applyPrice">篩選</AppButton>
        <AppButton
          v-if="priceQuery.min !== null || priceQuery.max !== null"
          variant="ghost" size="sm" @click="clearPrice"
        >
          清除
        </AppButton>
      </div>
    </div>

    <p v-if="products.length > 0" class="mb-4 figure text-xs text-ink-faint">
      已顯示 {{ products.length.toLocaleString() }} 件
    </p>

    <ul
      v-if="products.length > 0"
      class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4"
    >
      <li v-for="product in products" :key="product.productId">
        <ProductCard
          :product="product"
          :rating="ratings[product.productId] ?? null"
          :image-url="images[product.productId]?.listUrl ?? null"
        />
      </li>
    </ul>

    <EmptyState v-else title="這個類目下目前沒有上架商品。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        看全部商品
      </AppButton>
    </EmptyState>

    <!--
      載入更多，不是頁碼。keyset 分頁只知道「上一頁的最後一筆」，
      跳頁做不到——而商店的互動本來就是往下捲（ADR-0021 決策 4）
    -->
    <div v-if="hasMore" class="mt-8 flex justify-center">
      <AppButton variant="secondary" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '載入中⋯' : '載入更多' }}
      </AppButton>
    </div>
    <p
      v-else-if="products.length > 0"
      class="mt-8 text-center text-xs text-ink-faint"
    >
      已顯示全部 {{ products.length.toLocaleString() }} 件商品
    </p>
  </div>
</template>
