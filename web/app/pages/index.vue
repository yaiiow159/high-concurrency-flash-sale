<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type {
  ActivityView, ApiResponse, CategoryView, HomeLayoutView, HomeSectionView,
  ProductImageView, ProductRatingView, ProductView, RankedProductView,
} from '~/types/api'

/**
 * 首頁。版位順序、標題與內容全部由後台設定決定（`/api/v1/home`），
 * 這一頁只負責把每種版位畫出來。
 */
const { loadStatus } = useWishlist()
const { data: layoutData } = await useFetch<ApiResponse<HomeLayoutView>>('/api/v1/home')
const { data: activityData } = await useFetch<ApiResponse<ActivityView[]>>('/api/v1/activities')
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')
// 排行榜與版位一起在伺服器端取：它跟首頁本體一樣進 ISR 快取
const { data: rankingData } = await useFetch<ApiResponse<RankedProductView[]>>(
  '/api/v1/catalog/rankings?days=7&limit=5')
const rankings = computed(() => rankingData.value?.data ?? [])
/** 排行榜跟在限時搶購後面；沒有那個版位就排在最後。 */
const rankingAnchor = computed(() =>
  sections.value.find((section) => section.type === 'FLASH_SALE')?.sectionId ?? null)

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

/** 橫幅上的倒數跟著「最先能買」的活動走；沒有進行中的就跟著第一個。 */
const { now: serverNow } = useServerTime()
const spotlight = computed<ActivityView | null>(() =>
  activities.value.find((activity) => activity.purchasable) ?? activities.value[0] ?? null)

/** 評分與圖片在客戶端另外取：首頁是 ISR 快取的，併進來會讓新評價要等快取過期。 */
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})

async function loadDecorations() {
  const ids = [
    ...sections.value.flatMap((section) => section.products.map((product) => product.productId)),
    ...rankings.value.map((entry) => entry.product.productId),
  ]
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
    // 收藏狀態一次問完。少了這一步，列表上的愛心永遠是空心的
    await loadStatus([...new Set(ids)])
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

/** 最近看過。未登入時是空的，那一區整個不出現。 */
const auth = useAuthStore()
const recentlyViewed = ref<ProductView[]>([])

async function loadRecentlyViewed() {
  if (!auth.isAuthenticated) {
    return
  }
  try {
    const { request } = useApi()
    recentlyViewed.value = await request<ProductView[]>(
      '/api/v1/products/recently-viewed?limit=8', { authenticated: true })
  } catch {
    recentlyViewed.value = []
  }
}

onMounted(() => {
  void loadDecorations()
  void loadRecentlyViewed()
})

/** 分類入口的色塊：同一個分類永遠同一個顏色。 */
const CATEGORY_TONES = [
  'bg-[#fdeceb] text-[#e11d2b]',
  'bg-[#fff3e0] text-[#e8730c]',
  'bg-[#e8f4ff] text-[#1c6fd1]',
  'bg-[#e9f7ee] text-[#1f8a4c]',
  'bg-[#f3eefe] text-[#7a3fd1]',
  'bg-[#fff7d6] text-[#b07a00]',
] as const

function categoryTone(id: number) {
  return CATEGORY_TONES[id % CATEGORY_TONES.length]
}

const { seo } = useSeo()
seo({
  title: '閃購 — 限時搶購與熱銷商品',
  description: '限時搶購、熱門商品與當季選品，線上下單快速到貨。',
  path: '/',
})
</script>

<template>
  <div class="flex flex-col gap-10">
    <template v-for="section in sections" :key="section.sectionId">
      <!-- 主視覺 + 今日焦點：輪播佔三分之二，右側是最先能買的那檔活動 -->
      <div
        v-if="section.type === 'CAROUSEL'"
        class="grid gap-4"
        :class="spotlight ? 'lg:grid-cols-[minmax(0,1fr)_19rem]' : ''"
      >
        <HomeCarousel :slides="section.slides" />
        <NuxtLink
          v-if="spotlight"
          :to="`/seckill/${spotlight.activityId}`"
          class="group hidden lg:block"
        >
          <AppCard interactive class="flex h-full flex-col overflow-hidden">
            <div class="bg-promo flex items-center justify-between px-4 py-2.5 text-white">
              <span class="flex items-center gap-1.5 text-sm font-extrabold">
                <svg viewBox="0 0 24 24" class="h-4 w-4" fill="currentColor" aria-hidden="true">
                  <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />
                </svg>
                今日焦點
              </span>
              <span class="text-[11px] font-semibold text-white/85">
                限購 {{ spotlight.perUserLimit.toLocaleString() }} 件
              </span>
            </div>
            <div class="flex flex-1 flex-col gap-3 p-4">
              <ProductTile
                :seed="spotlight.skuId" :label="spotlight.productName"
                ratio="wide" class="transition-transform duration-300 group-hover:scale-[1.02]"
              />
              <h3 class="line-clamp-2 text-sm font-semibold leading-snug">
                {{ spotlight.productName }}
              </h3>
              <MoneyText :amount="spotlight.seckillPrice" size="xl" tone="danger" />
              <CountdownTimer
                :start-at="spotlight.startAt" :end-at="spotlight.endAt" :server-now="serverNow"
              />
              <StockIndicator
                class="mt-auto"
                :available="spotlight.availableStock" :total="spotlight.totalStock" compact
              />
            </div>
          </AppCard>
        </NuxtLink>
      </div>

      <!-- 限時搶購：品牌色橫幅 + 倒數，整頁只有這一區用漸層 -->
      <section
        v-else-if="section.type === 'FLASH_SALE' && activities.length > 0"
        :aria-labelledby="`section-${section.sectionId}`"
        class="overflow-hidden rounded shadow-rest"
      >
        <div class="bg-promo flex flex-wrap items-center justify-between gap-3 px-4 py-3 text-white sm:px-5">
          <div class="flex items-center gap-3">
            <h2
              :id="`section-${section.sectionId}`"
              class="flex items-center gap-1.5 text-xl font-extrabold tracking-tight"
            >
              <svg viewBox="0 0 24 24" class="h-5 w-5" fill="currentColor" aria-hidden="true">
                <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />
              </svg>
              {{ section.title }}
            </h2>
            <p v-if="section.subtitle" class="hidden text-sm text-white/85 sm:block">
              {{ section.subtitle }}
            </p>
          </div>
          <CountdownTimer
            v-if="spotlight"
            :start-at="spotlight.startAt" :end-at="spotlight.endAt" :server-now="serverNow"
            tone="light"
          />
        </div>
        <ul class="grid grid-cols-2 gap-3 bg-surface p-3 sm:grid-cols-3 sm:gap-4 sm:p-4 lg:grid-cols-4">
          <li v-for="activity in flashSaleItems(section)" :key="activity.activityId">
            <FlashSaleCard :activity="activity" />
          </li>
        </ul>
        <p class="border-t border-line bg-surface px-4 py-2 text-[11px] text-ink-faint">
          庫存為列表快取值，實際餘量以活動頁為準
        </p>
      </section>

      <!-- 分類入口：色塊 + 名稱，一眼掃過去就知道有哪些東西可以逛 -->
      <section
        v-else-if="section.type === 'CATEGORY_GRID' && categoryEntries.length > 0"
        :aria-labelledby="`section-${section.sectionId}`"
      >
        <SectionHeading :title="section.title" :more-to="{ path: '/products' }" more-label="全部商品">
          <template v-if="section.subtitle" #aside>
            <span class="text-sm text-ink-muted">{{ section.subtitle }}</span>
          </template>
        </SectionHeading>
        <ul class="grid grid-cols-4 gap-2 sm:grid-cols-6 sm:gap-3 lg:grid-cols-12">
          <li v-for="category in categoryEntries" :key="category.categoryId">
            <NuxtLink
              :to="{ path: '/products', query: { category: category.categoryId } }"
              class="group flex flex-col items-center gap-2 rounded bg-surface px-1 py-3
                     text-center shadow-rest transition hover:-translate-y-0.5 hover:shadow-lift"
            >
              <span
                class="grid h-12 w-12 place-items-center rounded-full text-lg font-extrabold
                       transition-transform group-hover:scale-110"
                :class="categoryTone(category.categoryId)"
                aria-hidden="true"
              >
                {{ category.name.slice(0, 1) }}
              </span>
              <span class="line-clamp-1 w-full text-xs font-medium text-ink group-hover:text-accent">
                {{ category.name }}
              </span>
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
      <RankingBoard
        v-if="section.sectionId === rankingAnchor && rankings.length > 0"
        :items="rankings" :ratings="ratings" :images="images" compact
        :title="`本週熱銷 TOP ${rankings.length}`"
        description="最近七天已付款訂單的銷量"
      />
    </template>
    <RankingBoard
      v-if="rankingAnchor === null && rankings.length > 0"
      :items="rankings" :ratings="ratings" :images="images" compact
      :title="`本週熱銷 TOP ${rankings.length}`"
      description="最近七天已付款訂單的銷量"
    />

    <!-- 最近看過放在版位之後：它是「回來繼續看」的入口，不是首頁的主張 -->
    <ProductRail
      v-if="recentlyViewed.length > 0"
      eyebrow="Recently Viewed"
      title="最近看過"
      :products="recentlyViewed"
      :ratings="ratings"
      :images="images"
      :more-to="null"
    />

    <EmptyState v-if="sections.length === 0" title="目前沒有可以逛的商品。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
