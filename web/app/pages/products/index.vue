<script setup lang="ts">
import type { ApiResponse, CategoryView, ProductView } from '~/types/api'

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

const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories',
)
const { data: productData } = await useFetch<ApiResponse<ProductView[]>>(
  '/api/v1/catalog/products',
  // key 帶上類目，否則切換類目時 Nuxt 會沿用同一份快取結果
  { query: { categoryId }, key: () => `products-${categoryId.value ?? 'all'}` },
)

const categories = computed(() => categoryData.value?.data ?? [])
const products = computed(() => productData.value?.data ?? [])

/** 把類目樹壓平成「父 › 子」的選項，兩層以內用巢狀清單反而更難掃 */
const categoryOptions = computed(() =>
  categories.value.flatMap((parent) => [
    { id: parent.categoryId, label: parent.name, depth: 0 },
    ...parent.children.map((child) => ({
      id: child.categoryId,
      label: child.name,
      depth: 1,
    })),
  ]),
)

useHead({ title: '全部商品' })
</script>

<template>
  <main class="mx-auto max-w-4xl px-5 py-10">
    <header class="flex flex-wrap items-baseline justify-between gap-3">
      <h1 class="text-3xl font-black tracking-tight">全部商品</h1>
      <NuxtLink to="/" class="text-sm text-[var(--accent)] hover:underline">
        限時搶購 →
      </NuxtLink>
    </header>

    <nav class="mt-6 flex flex-wrap gap-2" aria-label="類目篩選">
      <NuxtLink
        to="/products"
        class="rounded border px-3 py-1.5 text-sm transition"
        :class="categoryId === null
          ? 'border-[var(--accent)] text-[var(--accent)]'
          : 'border-[var(--line)] text-[var(--ink-muted)] hover:border-[var(--accent)]'"
      >
        全部
      </NuxtLink>
      <NuxtLink
        v-for="option in categoryOptions"
        :key="option.id"
        :to="{ path: '/products', query: { category: option.id } }"
        class="rounded border px-3 py-1.5 text-sm transition"
        :class="categoryId === option.id
          ? 'border-[var(--accent)] text-[var(--accent)]'
          : 'border-[var(--line)] text-[var(--ink-muted)] hover:border-[var(--accent)]'"
      >
        <span v-if="option.depth > 0" class="text-[var(--ink-muted)]">└ </span>{{ option.label }}
      </NuxtLink>
    </nav>

    <ul class="mt-6 grid gap-3 sm:grid-cols-2">
      <li v-for="product in products" :key="product.productId">
        <NuxtLink
          :to="`/products/${product.productId}`"
          class="flex h-full flex-col justify-between gap-4 rounded border border-[var(--line)]
                 bg-[var(--surface)] p-5 transition hover:border-[var(--accent)]"
        >
          <div>
            <div class="font-semibold">{{ product.name }}</div>
            <div v-if="product.brand" class="mt-1 text-sm text-[var(--ink-muted)]">
              {{ product.brand }}
            </div>
          </div>
          <div class="font-mono text-lg font-bold">
            NT$ {{ product.lowestPrice.toLocaleString() }}
            <!-- 多規格商品各 SKU 價格不同，列表只能顯示「起」價 -->
            <span class="text-sm font-normal text-[var(--ink-muted)]">起</span>
          </div>
        </NuxtLink>
      </li>
    </ul>

    <p v-if="products.length === 0" class="mt-10 text-[var(--ink-muted)]">
      這個類目下目前沒有上架商品。
    </p>
  </main>
</template>
