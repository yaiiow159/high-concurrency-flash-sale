<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import type { ProductSearchResult } from '~/types/api'

/**
 * 商品搜尋（ADR-0012）。
 *
 * <p><b>不做 SSR 也不做 ISR。</b> 搜尋結果隨關鍵字而異，
 * 快取它等於為每一種關鍵字組合各存一份，命中率趨近於零；
 * 而搜尋是使用者進站後才做的動作，首屏速度本來就不由它決定。
 *
 * <p>關鍵字放在網址上而不是只放在元件狀態裡——搜尋結果是會被分享、
 * 被加書籤、被上一頁回來的東西。只存在記憶體裡的話，
 * 使用者按上一頁會回到一個空的搜尋框。
 */
const route = useRoute()
const router = useRouter()
const { request } = useApi()

const keyword = ref((route.query.q as string) ?? '')
const brand = ref((route.query.brand as string) ?? '')
const result = ref<ProductSearchResult | null>(null)
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
    result.value = await request<ProductSearchResult>(`/api/v1/search/products?${params}`)
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '搜尋失敗'
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
    },
  })
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
  run()
}, { immediate: true })

useHead(() => ({ title: keyword.value ? `搜尋「${keyword.value}」` : '搜尋商品' }))
</script>

<template>
  <div>
    <PageHeader eyebrow="Search" title="搜尋商品" />

    <form class="flex flex-wrap gap-2" @submit.prevent="submit">
      <input
        v-model="keyword"
        type="search"
        placeholder="搜尋商品名稱或品牌"
        aria-label="搜尋關鍵字"
        class="h-11 min-w-0 flex-1 rounded-sm border border-line bg-surface px-4"
      >
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
