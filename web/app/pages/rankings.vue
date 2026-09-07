<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import type { ApiResponse, ProductImageView, ProductRatingView, RankedProductView } from '~/types/api'

/**
 * 排行榜。兩個區間就夠：七天看「現在紅什麼」，三十天看「一直在賣的是什麼」。
 * 更多區間只會讓人在按鈕之間換來換去，而榜單其實差不多。
 */
const WINDOWS = [
  { days: 7, label: '本週' },
  { days: 30, label: '本月' },
] as const

const route = useRoute()
const router = useRouter()
const days = computed(() => (String(route.query.days) === '30' ? 30 : 7))

// 榜單本體在伺服器端取，進 ISR 快取；圖片與評分在客戶端另外取
const { data, error: fetchError, pending } = await useFetch<ApiResponse<RankedProductView[]>>(
  () => `/api/v1/catalog/rankings?days=${days.value}&limit=20`,
  { key: () => `rankings-${days.value}`, watch: [days] },
)
const items = computed(() => data.value?.data ?? [])

const { request } = useApi()
const { loadStatus } = useWishlist()
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})

async function decorate() {
  const ids = items.value.map((entry) => entry.product.productId)
  if (ids.length === 0) {
    return
  }
  try {
    const query = ids.join(',')
    const [rating, image] = await Promise.all([
      request<Record<number, ProductRatingView>>(`/api/v1/catalog/products/ratings?productIds=${query}`),
      request<Record<number, ProductImageView>>(`/api/v1/catalog/products/images?productIds=${query}`),
    ])
    ratings.value = rating
    images.value = image
    await loadStatus(ids)
  } catch (cause) {
    console.warn(errorMessage(cause, '評分與圖片載入失敗'))
  }
}

onMounted(decorate)
watch(items, () => { void decorate() })

function pick(window: number) {
  router.replace({ query: window === 7 ? {} : { days: String(window) } })
}

const { seo } = useSeo()
watchEffect(() => {
  seo({
    title: `${days.value === 30 ? '本月' : '本週'}熱銷排行`,
    description: '依已付款訂單銷量排序的熱銷商品榜。',
    path: '/rankings',
  })
})
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex flex-wrap items-end justify-between gap-4">
      <PageHeader eyebrow="Best sellers" title="熱銷排行" description="依已付款訂單的銷量排序，已下架的商品不列入" />
      <div class="flex gap-1 rounded-sm border border-line bg-surface p-1" role="tablist" aria-label="統計區間">
        <button
          v-for="window in WINDOWS"
          :key="window.days"
          type="button"
          role="tab"
          :aria-selected="days === window.days"
          class="h-8 rounded-sm px-4 text-sm transition-colors"
          :class="days === window.days ? 'bg-accent-soft font-semibold text-accent' : 'text-ink-muted hover:text-ink'"
          @click="pick(window.days)"
        >
          {{ window.label }}
        </button>
      </div>
    </div>

    <p v-if="fetchError" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger" role="alert">
      排行榜暫時無法載入
    </p>

    <div v-if="pending && items.length === 0" class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
      <SkeletonCard v-for="n in 6" :key="n" />
    </div>

    <RankingBoard
      v-else-if="items.length > 0"
      :items="items"
      :ratings="ratings"
      :images="images"
      :title="`${days === 30 ? '本月' : '本週'}熱銷 TOP ${items.length}`"
      :more-to="null"
    />

    <EmptyState
      v-else
      title="這段期間還沒有銷售紀錄。"
      description="有訂單付款後，榜單就會出現在這裡。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        瀏覽全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
