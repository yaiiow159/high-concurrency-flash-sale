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

/** 把類目樹壓平成選項，兩層以內用巢狀清單反而更難掃 */
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

const activeCategoryName = computed(() =>
  categoryOptions.value.find((option) => option.id === categoryId.value)?.label ?? null,
)

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
        class="rounded-sm border px-3 py-1.5 text-sm transition-colors"
        :class="categoryId === null
          ? 'border-accent text-accent'
          : 'border-line text-ink-muted hover:border-line-strong hover:text-ink'"
      >
        全部
      </NuxtLink>
      <NuxtLink
        v-for="option in categoryOptions"
        :key="option.id"
        :to="{ path: '/products', query: { category: option.id } }"
        class="rounded-sm border px-3 py-1.5 text-sm transition-colors"
        :class="[
          categoryId === option.id
            ? 'border-accent text-accent'
            : 'border-line text-ink-muted hover:border-line-strong hover:text-ink',
          option.depth > 0 ? 'ml-1' : '',
        ]"
      >
        <span v-if="option.depth > 0" class="mr-1 text-ink-faint">└</span>{{ option.label }}
      </NuxtLink>
    </nav>

    <ul v-if="products.length > 0" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <li v-for="product in products" :key="product.productId">
        <NuxtLink :to="`/products/${product.productId}`" class="block h-full">
          <AppCard interactive class="flex h-full flex-col justify-between gap-6 p-5">
            <div>
              <p v-if="product.brand" class="eyebrow mb-1.5">{{ product.brand }}</p>
              <h2 class="font-semibold leading-snug">{{ product.name }}</h2>
            </div>
            <div class="flex items-baseline gap-1.5">
              <MoneyText :amount="product.lowestPrice" size="lg" />
              <!-- 多規格商品各 SKU 價格不同，列表只能顯示「起」價 -->
              <span class="text-xs text-ink-faint">起</span>
            </div>
          </AppCard>
        </NuxtLink>
      </li>
    </ul>

    <EmptyState v-else title="這個類目下目前沒有上架商品。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        看全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
