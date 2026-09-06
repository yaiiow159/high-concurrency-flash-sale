<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import type {
  ActivityView, ApiResponse, CategoryView, HomeLayoutView, HomeSectionView,
  ProductImageView, ProductRatingView,
} from '~/types/api'

/**
 * 首頁。版位順序、標題與內容全部由後台設定決定（`/api/v1/home`），
 * 這一頁只負責把每種版位畫出來。
 */
const { data: layoutData } = await useFetch<ApiResponse<HomeLayoutView>>('/api/v1/home')
const { data: activityData } = await useFetch<ApiResponse<ActivityView[]>>('/api/v1/activities')
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')

const sections = computed(() => layoutData.value?.data?.sections ?? [])
const activities = computed(() => activityData.value?.data ?? [])

/**
 * 分類入口只取第二層。根類目只有一個，當入口等於沒有分；
 * 第三層有 210 個，全列出來會把商品擠出畫面。
 */
const categoryEntries = computed(() =>
  (categoryData.value?.data ?? []).flatMap((root) => root.children ?? []).slice(0, 12))

function flashSaleItems(section: HomeSectionView) {
  return activities.value.slice(0, section.type === 'FLASH_SALE' ? 8 : 0)
}

/** 評分與圖片在客戶端另外取：首頁是 ISR 快取的，併進來會讓新評價要等快取過期。 */
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})

async function loadDecorations() {
  const ids = sections.value.flatMap((section) =>
    section.products.map((product) => product.productId))
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

/** 規則型版位有對應的完整列表可以去，人工選品沒有——那份清單只存在於首頁。 */
function moreLink(section: HomeSectionView) {
  const map: Record<string, string> = {
    熱門商品: 'BEST_SELLING',
    最新上架: 'NEWEST',
    好評推薦: 'RATING',
  }
  const sort = map[section.title]
  return sort ? { path: '/products', query: { sort } } : { path: '/products' }
}

onMounted(loadDecorations)

const { seo } = useSeo()
seo({
  title: '閃購 — 限時搶購與熱銷商品',
  description: '限時搶購、熱門商品與當季選品，線上下單快速到貨。',
  path: '/',
})
</script>

<template>
  <div class="flex flex-col gap-12">
    <template v-for="section in sections" :key="section.sectionId">
      <HomeCarousel v-if="section.type === 'CAROUSEL'" :slides="section.slides" />

      <!-- 限時搶購：庫存與售罄狀態是這一區最需要一眼看到的事 -->
      <section
        v-else-if="section.type === 'FLASH_SALE' && activities.length > 0"
        :aria-labelledby="`section-${section.sectionId}`"
      >
        <div class="mb-4 flex items-end justify-between gap-4">
          <div>
            <p class="eyebrow mb-1">Flash Sale</p>
            <h2
              :id="`section-${section.sectionId}`"
              class="text-xl font-bold tracking-tight sm:text-2xl"
            >
              {{ section.title }}
            </h2>
            <p v-if="section.subtitle" class="mt-1 text-xs text-ink-faint">
              {{ section.subtitle }}
            </p>
          </div>
          <p class="text-xs text-ink-faint">庫存為列表快取值，實際餘量以活動頁為準</p>
        </div>

        <ul class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
          <li v-for="activity in flashSaleItems(section)" :key="activity.activityId">
            <NuxtLink :to="`/seckill/${activity.activityId}`" class="group block h-full">
              <AppCard interactive class="flex h-full flex-col overflow-hidden">
                <div class="relative">
                  <ProductTile
                    :seed="activity.skuId" :label="activity.productName"
                    class="transition-transform duration-300 group-hover:scale-[1.03]"
                  />
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
                  <!-- 窄卡片上並排會把「餘 996」擠到換行，數字被拆成兩行比不顯示更糟 -->
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

      <section
        v-else-if="section.type === 'CATEGORY_GRID' && categoryEntries.length > 0"
        :aria-labelledby="`section-${section.sectionId}`"
      >
        <h2 :id="`section-${section.sectionId}`" class="eyebrow mb-3">{{ section.title }}</h2>
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
        v-else-if="section.type === 'PRODUCT_RAIL'"
        :title="section.title"
        :description="section.subtitle"
        :products="section.products"
        :ratings="ratings"
        :images="images"
        :ranked="section.title === '熱門商品'"
        :more-to="moreLink(section)"
      />
    </template>

    <EmptyState v-if="sections.length === 0" title="目前沒有可以逛的商品。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
