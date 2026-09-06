<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { ProductView } from '~/types/api'

const auth = useAuthStore()
const { list, loadStatus } = useWishlist()


const items = ref<ProductView[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref('')

async function load() {
  if (!auth.isAuthenticated) {
    loading.value = false
    return
  }
  loading.value = true
  try {
    const page = await list(0, 50)
    items.value = page.items
    total.value = page.total
    await loadStatus(page.items.map((p) => p.productId))
  } catch (cause) {
    error.value = errorMessage(cause, '讀取收藏失敗')
  } finally {
    loading.value = false
  }
}

/**
 * 取消收藏後重新載入。
 *
 * 就地移除比較快，但那會讓「總數」與清單各自為政；
 * 這一頁不是熱路徑，重新問一次比較不會錯。
 */
onMounted(load)
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    void load()
  }
})

const { seo } = useSeo()
seo({ title: '我的收藏', noindex: true })
</script>

<template>
  <div class="flex flex-col gap-6">
    <div>
      <p class="eyebrow mb-1">Wishlist</p>
      <h1 class="text-2xl font-bold tracking-tight">我的收藏</h1>
      <p v-if="total > 0" class="mt-1 text-sm text-ink-faint">
        共 <span class="figure">{{ total }}</span> 件
      </p>
    </div>

    <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger">{{ error }}</p>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <ul v-else-if="items.length > 0" class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
      <li v-for="product in items" :key="product.productId">
        <ProductCard :product="product" />
      </li>
    </ul>

    <EmptyState
      v-else-if="!loading"
      title="還沒有收藏任何商品。"
      description="在商品上按愛心就會出現在這裡。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        去逛逛
      </AppButton>
    </EmptyState>
  </div>
</template>
