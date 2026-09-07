<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { ProductImageView, ProductRatingView, ProductView } from '~/types/api'

/**
 * 瀏覽紀錄。清單本身後端只留最近 20 筆（同一件商品只留最後一次），
 * 這一頁不做分頁——會想翻到第三頁找「上週看過的那個」的人，用搜尋比較快。
 */
const auth = useAuthStore()
const { request } = useApi()
const { loadStatus } = useWishlist()

const items = ref<ProductView[]>([])
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})
const loading = ref(true)
const clearing = ref(false)
const error = ref<string | null>(null)

async function load() {
  if (!auth.isAuthenticated) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = null
  try {
    items.value = await request<ProductView[]>('/api/v1/products/recently-viewed?limit=20',
      { authenticated: true })
    const ids = items.value.map((product) => product.productId)
    if (ids.length > 0) {
      const query = ids.join(',')
      const [rating, image] = await Promise.all([
        request<Record<number, ProductRatingView>>(`/api/v1/catalog/products/ratings?productIds=${query}`)
          .catch(() => ({})),
        request<Record<number, ProductImageView>>(`/api/v1/catalog/products/images?productIds=${query}`)
          .catch(() => ({})),
      ])
      ratings.value = rating
      images.value = image
      await loadStatus(ids)
    }
  } catch (cause) {
    error.value = errorMessage(cause, '讀取瀏覽紀錄失敗')
  } finally {
    loading.value = false
  }
}

/** 清除要確認：這不是可以復原的動作，而誤按的代價是「我剛剛看的那個找不到了」。 */
async function clearAll() {
  if (!confirm('清除全部瀏覽紀錄？此動作無法復原。')) {
    return
  }
  clearing.value = true
  error.value = null
  try {
    await request<void>('/api/v1/products/recently-viewed', { method: 'DELETE', authenticated: true })
    items.value = []
  } catch (cause) {
    error.value = errorMessage(cause, '清除失敗')
  } finally {
    clearing.value = false
  }
}

onMounted(load)
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    void load()
  }
})

const { seo } = useSeo()
seo({ title: '瀏覽紀錄', noindex: true })
</script>

<template>
  <div class="flex flex-col gap-6">
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <p class="eyebrow mb-1">Recently viewed</p>
        <h1 class="text-2xl font-bold tracking-tight">瀏覽紀錄</h1>
        <p class="mt-1 text-sm text-ink-faint">最近看過的 20 件商品，同一件只留最後一次</p>
      </div>
      <AppButton
        v-if="auth.isAuthenticated && items.length > 0"
        variant="ghost" size="sm" :disabled="clearing" @click="clearAll"
      >
        {{ clearing ? '清除中⋯' : '清除全部' }}
      </AppButton>
    </div>

    <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger" role="alert">{{ error }}</p>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div v-else-if="loading" class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
      <SkeletonCard v-for="n in 8" :key="n" />
    </div>

    <ul v-else-if="items.length > 0" class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
      <li v-for="product in items" :key="product.productId">
        <ProductCard
          :product="product"
          :rating="ratings[product.productId] ?? null"
          :image-url="images[product.productId]?.listUrl ?? null"
        />
      </li>
    </ul>

    <EmptyState
      v-else
      title="還沒有瀏覽紀錄。"
      description="看過的商品會出現在這裡，方便你回頭找。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        去逛逛
      </AppButton>
    </EmptyState>
  </div>
</template>
