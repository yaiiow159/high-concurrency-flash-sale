<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import type {
  ActivityView, ApiResponse, CategoryView, ProductImageView, ProductPage, ProductRatingView,
} from '~/types/api'

/**
 * 首頁。
 *
 * <h2>首頁是入口，不是一個功能頁</h2>
 *
 * 先前這一頁只有秒殺活動網格——三張卡就結束了，逛不下去。
 * 電商的首頁要回答「我現在可以去哪裡」，而不是「這裡有什麼」。
 *
 * <h2>四個區塊全部用既有資料，沒有新的表或端點</h2>
 *
 * 限時搶購（活動）、熱銷（`sort=BEST_SELLING`）、最新上架（`sort=NEWEST`）、
 * 分類入口（類目樹）——這四份資料本來就都在，只是首頁一份都沒用。
 *
 * ISR 快取 60 秒。四個請求都在伺服器端一次做完，
 * 對使用者是一次往返；快取命中時是零次。
 */
const { data: activityData } = await useFetch<ApiResponse<ActivityView[]>>(
  '/api/v1/activities')
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')
const { data: bestSellingData } = await useFetch<ApiResponse<ProductPage>>(
  '/api/v1/catalog/products', { query: { sort: 'BEST_SELLING', size: 8 }, key: 'home-best' })
const { data: newestData } = await useFetch<ApiResponse<ProductPage>>(
  '/api/v1/catalog/products', { query: { sort: 'NEWEST', size: 8 }, key: 'home-newest' })

const activities = computed(() => activityData.value?.data ?? [])
const bestSelling = computed(() => bestSellingData.value?.data?.items ?? [])
const newest = computed(() => newestData.value?.data?.items ?? [])

/**
 * 分類入口只取**第二層**。
 *
 * 根類目只有一個（「3C 產品」），當入口等於沒有分。
 * 第三層有 210 個，全列出來就是上一輪踩過的那個坑——
 * 類目牆把內容擠出畫面。第二層是唯一一個「數量剛好、粒度也剛好」的層級。
 */
const categoryEntries = computed(() =>
  (categoryData.value?.data ?? []).flatMap((root) => root.children ?? []).slice(0, 12))

/**
 * 評分。
 *
 * 與商品列表同一個做法：在客戶端另外取，不併進這一頁的 SSR——
 * 首頁是 ISR 快取的，評分跟著被快取會讓新評價要等快取過期才看得到。
 */
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})

async function loadRatings() {
  const ids = [...bestSelling.value, ...newest.value].map((product) => product.productId)
  if (ids.length === 0) {
    return
  }
  try {
    const { request } = useApi()
    const query = [...new Set(ids)].join(',')
    const [rating, image] = await Promise.all([
      request<Record<number, ProductRatingView>>(
        `/api/v1/catalog/products/ratings?productIds=${query}`),
      request<Record<number, ProductImageView>>(
        `/api/v1/catalog/products/images?productIds=${query}`),
    ])
    ratings.value = rating
    images.value = image
  } catch (cause) {
    // fail-open：評分或圖片掛掉不該讓人連首頁都看不到
    ratings.value = {}
    images.value = {}
    console.warn(errorMessage(cause, '評分與圖片載入失敗'))
  }
}

onMounted(loadRatings)

useHead({ title: '閃購 — 限時搶購與熱銷商品' })
</script>

<template>
  <div class="flex flex-col gap-12">
    <!-- 限時搶購擺第一：它是這個站的主張，不是一個普通區塊 -->
    <section v-if="activities.length > 0" aria-labelledby="flash-heading">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <p class="eyebrow mb-1">Flash Sale</p>
          <h2 id="flash-heading" class="text-xl font-bold tracking-tight sm:text-2xl">
            限時搶購
          </h2>
        </div>
        <p class="text-xs text-ink-faint">庫存為列表快取值，實際餘量以活動頁為準</p>
      </div>

      <ul class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
        <li v-for="activity in activities" :key="activity.activityId">
          <NuxtLink :to="`/seckill/${activity.activityId}`" class="group block h-full">
            <AppCard interactive class="flex h-full flex-col overflow-hidden">
              <div class="relative">
                <ProductTile
                  :seed="activity.skuId" :label="activity.productName"
                  class="transition-transform duration-300 group-hover:scale-[1.03]"
                />
                <!-- 售罄是這一頁最需要一眼看到的事，蓋在視覺上而不是藏在文字裡 -->
                <div
                  v-if="activity.availableStock <= 0"
                  class="absolute inset-0 flex items-center justify-center bg-black/55"
                >
                  <span class="rounded-sm bg-white/95 px-3 py-1 text-sm font-semibold text-danger">
                    已售罄
                  </span>
                </div>
                <span
                  v-else
                  class="absolute left-2 top-2 rounded-sm bg-danger px-1.5 py-0.5
                         text-xs font-semibold text-white shadow-rest"
                >
                  限時
                </span>
              </div>
              <div class="flex flex-1 flex-col justify-between gap-3 p-3.5 sm:p-4">
                <div>
                  <h3 class="text-sm font-medium leading-snug sm:text-base">
                    {{ activity.productName }}
                  </h3>
                  <p class="mt-1 text-xs text-ink-faint">
                    每人限購 <span class="figure">{{ activity.perUserLimit }}</span> 件
                  </p>
                </div>
                <!--
                  窄卡片上價格與餘量並排會把「餘 996」擠到換行，
                  數字被拆成兩行比不顯示更糟。改成上下堆疊，寬螢幕才並排。
                -->
                <div
                  class="flex flex-col gap-0.5 sm:flex-row sm:items-end
                         sm:justify-between sm:gap-2"
                >
                  <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
                  <span class="figure whitespace-nowrap text-xs text-ink-muted">
                    餘 {{ activity.availableStock }}
                  </span>
                </div>
              </div>
            </AppCard>
          </NuxtLink>
        </li>
      </ul>
    </section>

    <!-- 分類入口：讓 225 個類目有一個從首頁進得去的門 -->
    <section v-if="categoryEntries.length > 0" aria-labelledby="category-heading">
      <h2 id="category-heading" class="eyebrow mb-3">逛分類</h2>
      <ul class="flex flex-wrap gap-2">
        <li v-for="category in categoryEntries" :key="category.categoryId">
          <NuxtLink
            :to="{ path: '/products', query: { category: category.categoryId } }"
            class="inline-flex rounded-full border border-line bg-surface px-4 py-2
                   text-sm text-ink-muted shadow-rest transition-colors
                   hover:border-accent hover:text-accent"
          >
            {{ category.name }}
          </NuxtLink>
        </li>
      </ul>
    </section>

    <ProductRail
      v-if="bestSelling.length > 0"
      eyebrow="Best Sellers"
      title="熱銷排行"
      description="依實際售出件數排序，退貨會扣回"
      :products="bestSelling"
      :ratings="ratings"
      :images="images"
      ranked
      :more-to="{ path: '/products', query: { sort: 'BEST_SELLING' } }"
    />

    <ProductRail
      v-if="newest.length > 0"
      eyebrow="New Arrivals"
      title="最新上架"
      :products="newest"
      :ratings="ratings"
      :images="images"
      :more-to="{ path: '/products' }"
    />

    <EmptyState
      v-if="activities.length === 0 && bestSelling.length === 0 && newest.length === 0"
      title="目前沒有可以逛的商品。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
