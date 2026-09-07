<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import type { ProductSearchResult, ProductSearchSort } from '~/types/api'

/** 商品搜尋（ADR-0012）。 */
const route = useRoute()
const router = useRouter()
const { request } = useApi()

const SORT_OPTIONS: { value: ProductSearchSort, label: string }[] = [
  { value: 'RELEVANCE', label: '最相關' },
  { value: 'PRICE_ASC', label: '價格低到高' },
  { value: 'PRICE_DESC', label: '價格高到低' },
  { value: 'RATING', label: '評分最高' },
  { value: 'NEWEST', label: '最新上架' },
]
const RATING_OPTIONS = [4, 3] as const

function readRating(raw: unknown): number | null {
  const value = Number(raw)
  return (RATING_OPTIONS as readonly number[]).includes(value) ? value : null
}

function readSort(raw: unknown): ProductSearchSort {
  return SORT_OPTIONS.some((option) => option.value === raw) ? raw as ProductSearchSort : 'RELEVANCE'
}

const keyword = ref((route.query.q as string) ?? '')
const brand = ref((route.query.brand as string) ?? '')
/**
 * 篩選與排序全部住在網址裡：分享、回上一頁、重新整理都要回到同一份結果。
 * 這裡的 ref 只是輸入框的暫存，送出才寫進 query。
 */
const minInput = ref((route.query.minPrice as string) ?? '')
const maxInput = ref((route.query.maxPrice as string) ?? '')
const minRating = ref<number | null>(readRating(route.query.minRating))
const inStock = ref(route.query.inStock === '1')
const sort = ref<ProductSearchSort>(readSort(route.query.sort))
const result = ref<ProductSearchResult | null>(null)

/** 有沒有任何篩選在作用。有的話才顯示「清除篩選」，沒有時那顆按鈕只是噪音。 */
const filtered = computed(() =>
  minInput.value !== '' || maxInput.value !== '' || minRating.value !== null || inStock.value)
const loading = ref(false)
const error = ref<string | null>(null)

async function run() {
  loading.value = true
  error.value = null
  try {
    const params = new URLSearchParams({ q: keyword.value })
    if (brand.value) {
      params.set('brand', brand.value)
    }
    if (minInput.value.trim() !== '') {
      params.set('minPrice', minInput.value.trim())
    }
    if (maxInput.value.trim() !== '') {
      params.set('maxPrice', maxInput.value.trim())
    }
    if (minRating.value !== null) {
      params.set('minRating', String(minRating.value))
    }
    if (inStock.value) {
      params.set('inStock', 'true')
    }
    if (sort.value !== 'RELEVANCE') {
      params.set('sort', sort.value)
    }
    result.value = await request<ProductSearchResult>(`/api/v1/search/products?${params}`)
  } catch (cause) {
    error.value = errorMessage(cause, '搜尋失敗')
  } finally {
    loading.value = false
  }
}

/** 送出時把條件寫進網址，讓結果可以被分享與回上一頁 */
async function submit() {
  await router.push({
    query: {
      ...(keyword.value ? { q: keyword.value } : {}),
      ...(brand.value ? { brand: brand.value } : {}),
      ...(minInput.value.trim() ? { minPrice: minInput.value.trim() } : {}),
      ...(maxInput.value.trim() ? { maxPrice: maxInput.value.trim() } : {}),
      ...(minRating.value !== null ? { minRating: String(minRating.value) } : {}),
      ...(inStock.value ? { inStock: '1' } : {}),
      ...(sort.value !== 'RELEVANCE' ? { sort: sort.value } : {}),
    },
  })
}

function pickRating(stars: number) {
  minRating.value = minRating.value === stars ? null : stars
  submit()
}

function toggleInStock() {
  inStock.value = !inStock.value
  submit()
}

function changeSort(event: Event) {
  sort.value = readSort((event.target as HTMLSelectElement).value)
  submit()
}

function clearFilters() {
  minInput.value = ''
  maxInput.value = ''
  minRating.value = null
  inStock.value = false
  submit()
}

function pickBrand(name: string) {
  brand.value = brand.value === name ? '' : name
  submit()
}

// 網址變了就重跑。這樣「按上一頁」與「點分面」走的是同一條路徑，
// 不必為兩者各寫一份邏輯
watch(() => route.query, () => {
  keyword.value = (route.query.q as string) ?? ''
  brand.value = (route.query.brand as string) ?? ''
  minInput.value = (route.query.minPrice as string) ?? ''
  maxInput.value = (route.query.maxPrice as string) ?? ''
  minRating.value = readRating(route.query.minRating)
  inStock.value = route.query.inStock === '1'
  sort.value = readSort(route.query.sort)
  run()
}, { immediate: true })

/** 搜尋建議。 */
const suggestions = ref<string[]>([])
const suggestOpen = ref(false)
let suggestTimer: ReturnType<typeof setTimeout> | null = null

function onKeywordInput() {
  if (suggestTimer) {
    clearTimeout(suggestTimer)
  }
  const term = keyword.value.trim()
  if (term.length === 0) {
    suggestions.value = []
    suggestOpen.value = false
    return
  }
  suggestTimer = setTimeout(async () => {
    try {
      suggestions.value = await request<string[]>(
        `/api/v1/search/suggestions?q=${encodeURIComponent(term)}`)
      suggestOpen.value = suggestions.value.length > 0
    } catch {
      suggestions.value = []
      suggestOpen.value = false
    }
  }, 200)
}

/** 延遲關閉建議清單。 不延遲的話，滑鼠按下建議的瞬間輸入框先失焦、清單先消失， 那一下點擊就落到空氣裡。 */
function closeSuggestionsSoon() {
  setTimeout(() => { suggestOpen.value = false }, 120)
}

function pick(suggestion: string) {
  keyword.value = suggestion
  suggestOpen.value = false
  submit()
}

const { seo } = useSeo()
// 搜尋結果頁 noindex：每一組關鍵字都是一個網址，收錄它們只會產生大量
// 內容重複的低品質頁面，而那會拖累整站的評價
watchEffect(() => {
  seo({
    title: keyword.value ? `搜尋「${keyword.value}」` : '搜尋商品',
    noindex: true,
  })
})
</script>

<template>
  <div>
    <PageHeader eyebrow="Search" title="搜尋商品" />

    <form class="flex flex-wrap gap-2" @submit.prevent="submit">
      <div class="relative min-w-0 flex-1">
        <input
          v-model="keyword"
          type="search"
          placeholder="搜尋商品名稱或品牌"
          aria-label="搜尋關鍵字"
          role="combobox"
          :aria-expanded="suggestOpen"
          aria-autocomplete="list"
          class="h-11 w-full rounded-sm border border-line bg-surface px-4 shadow-rest"
          @input="onKeywordInput"
          @keydown.escape="suggestOpen = false"
          @blur="closeSuggestionsSoon"
        >
        <ul
          v-if="suggestOpen"
          class="absolute left-0 right-0 top-12 z-10 overflow-hidden rounded-sm border
                 border-line bg-surface shadow-lift"
        >
          <li v-for="suggestion in suggestions" :key="suggestion">
            <button
              type="button"
              class="block w-full truncate px-4 py-2 text-left text-sm
                     transition-colors hover:bg-sunken"
              @mousedown.prevent="pick(suggestion)"
            >
              {{ suggestion }}
            </button>
          </li>
        </ul>
      </div>
      <AppButton type="submit" size="lg" :disabled="loading">
        {{ loading ? '搜尋中⋯' : '搜尋' }}
      </AppButton>
    </form>

    <!--
      降級提示。不講的話，使用者會以為「就是搜不到」而不是「現在搜得不準」，
      然後去客服說商品不見了。
    -->
    <p
      v-if="result?.degraded"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
      role="status"
    >
      搜尋服務暫時不穩定，目前顯示的是簡化結果，排序與篩選可能不完整。
    </p>

    <p
      v-if="error"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <!-- 分面。降級時後端回空物件，這一區自動不顯示 -->
    <div
      v-if="result && Object.keys(result.facets).length > 0"
      class="mt-6 flex flex-wrap items-center gap-2"
    >
      <span class="eyebrow">品牌</span>
      <button
        v-for="(count, name) in result.facets"
        :key="name"
        type="button"
        :aria-pressed="brand === name"
        class="h-9 rounded-sm border px-3 text-sm transition-colors"
        :class="brand === name
          ? 'border-cta bg-accent-soft font-medium text-accent'
          : 'border-line hover:border-line-strong'"
        @click="pickBrand(String(name))"
      >
        {{ name }}
        <span class="figure ml-1 text-ink-faint">{{ count }}</span>
      </button>
    </div>

    <!-- 篩選與排序。降級時後端會忽略評分與有貨，上方的提示已經說了「篩選可能不完整」 -->
    <div class="mt-4 flex flex-wrap items-center gap-x-4 gap-y-3 rounded-sm border border-line bg-surface px-4 py-3">
      <div class="flex items-center gap-2">
        <span class="eyebrow">價格</span>
        <label class="sr-only" for="search-min-price">最低價</label>
        <input
          id="search-min-price" v-model="minInput" type="number" min="0" inputmode="numeric"
          placeholder="最低" class="field figure w-24 !py-1.5 text-xs" @keyup.enter="submit"
        >
        <span class="text-xs text-ink-faint">–</span>
        <label class="sr-only" for="search-max-price">最高價</label>
        <input
          id="search-max-price" v-model="maxInput" type="number" min="0" inputmode="numeric"
          placeholder="最高" class="field figure w-24 !py-1.5 text-xs" @keyup.enter="submit"
        >
        <AppButton variant="secondary" size="sm" @click="submit">套用</AppButton>
      </div>

      <div class="flex items-center gap-2">
        <span class="eyebrow">評分</span>
        <button
          v-for="stars in RATING_OPTIONS"
          :key="stars"
          type="button"
          :aria-pressed="minRating === stars"
          class="h-8 rounded-sm border px-3 text-xs transition-colors"
          :class="minRating === stars
            ? 'border-cta bg-accent-soft font-medium text-accent'
            : 'border-line hover:border-line-strong'"
          @click="pickRating(stars)"
        >
          {{ stars }} 星以上
        </button>
      </div>

      <button
        type="button"
        :aria-pressed="inStock"
        class="h-8 rounded-sm border px-3 text-xs transition-colors"
        :class="inStock
          ? 'border-cta bg-accent-soft font-medium text-accent'
          : 'border-line hover:border-line-strong'"
        @click="toggleInStock"
      >
        只看有貨
      </button>

      <label class="ml-auto flex items-center gap-2 text-xs text-ink-muted">
        排序
        <select :value="sort" class="field !w-auto !py-1.5 text-xs" @change="changeSort">
          <option v-for="option in SORT_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </option>
        </select>
      </label>

      <button
        v-if="filtered"
        type="button"
        class="text-xs text-ink-muted underline-offset-4 hover:text-ink hover:underline"
        @click="clearFilters"
      >
        清除篩選
      </button>
    </div>

    <div v-if="loading" class="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <SkeletonCard v-for="n in 6" :key="n" />
    </div>

    <template v-else-if="result">
      <p v-if="result.total > 0" class="mt-6 text-sm text-ink-muted">
        找到 <span class="figure">{{ result.total }}</span> 項商品
      </p>

      <ul v-if="result.hits.length > 0" class="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <li v-for="hit in result.hits" :key="hit.productId">
          <NuxtLink :to="`/products/${hit.productId}`" class="block">
            <AppCard interactive class="overflow-hidden">
              <ProductTile :seed="hit.productId" :label="hit.name" />
              <div class="p-4">
                <p v-if="hit.brand" class="eyebrow">{{ hit.brand }}</p>
                <p class="mt-1 truncate font-medium">{{ hit.name }}</p>
                <div class="mt-1 flex items-center gap-2 text-[11px] text-ink-faint">
                  <template v-if="hit.ratingCount > 0">
                    <StarRating :value="hit.ratingAverage" size="sm" />
                    <span class="figure">({{ hit.ratingCount }})</span>
                  </template>
                  <span v-else>尚無評價</span>
                  <span v-if="!hit.inStock && !result.degraded" class="ml-auto text-danger">缺貨中</span>
                </div>
                <!--
                  價格是索引當下的快照，允許落後數秒。
                  點進商品頁會重新從 Catalog 讀，結帳完全不碰這份索引。
                -->
                <MoneyText :amount="hit.lowestPrice" class="mt-2" />
              </div>
            </AppCard>
          </NuxtLink>
        </li>
      </ul>

      <EmptyState
        v-else
        title="沒有符合的商品"
        hint="換個關鍵字，或直接瀏覽全部商品。"
      >
        <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
          瀏覽全部商品
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
