<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import type { ApiResponse, CategoryView, ProductRatingView, ProductView } from '~/types/api'

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

async function loadRatings() {
  const ids = products.value.map((product) => product.productId)
  if (ids.length === 0) {
    return
  }
  try {
    const { request } = useApi()
    ratings.value = await request<Record<number, ProductRatingView>>(
      `/api/v1/catalog/products/ratings?productIds=${ids.join(',')}`)
  } catch {
    ratings.value = {}
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

    <ul
      v-if="products.length > 0"
      class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4"
    >
      <li v-for="product in products" :key="product.productId">
        <NuxtLink :to="`/products/${product.productId}`" class="group block h-full">
          <AppCard interactive class="flex h-full flex-col overflow-hidden">
            <!-- 目錄沒有圖片欄位，用 productId 推導的確定性色塊給網格視覺重量 -->
            <ProductTile
              :seed="product.productId" :label="product.name"
              class="transition-transform duration-300 group-hover:scale-[1.03]"
            />
            <div class="flex flex-1 flex-col justify-between gap-3 p-3.5 sm:p-4">
              <div>
                <p v-if="product.brand" class="eyebrow mb-1">{{ product.brand }}</p>
                <h2 class="text-sm font-medium leading-snug sm:text-base">{{ product.name }}</h2>

                <!--
                  沒有評價的商品**不顯示空星星**，整行留白。
                  一排灰星星讀起來像「被評了 0 分」，
                  而「還沒有人評價」與「評價很差」是完全不同的兩件事
                -->
                <p
                  v-if="ratings[product.productId]?.count"
                  class="mt-1.5 flex items-center gap-1.5"
                >
                  <StarRating :value="ratings[product.productId]!.average" size="sm" />
                  <span class="figure text-xs text-ink-faint">
                    {{ ratings[product.productId]!.count.toLocaleString() }}
                  </span>
                </p>
              </div>
              <div class="flex items-baseline gap-1">
                <MoneyText :amount="product.lowestPrice" size="lg" />
                <!-- 多規格商品各 SKU 價格不同，列表只能顯示「起」價 -->
                <span class="text-xs text-ink-faint">起</span>
              </div>
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
