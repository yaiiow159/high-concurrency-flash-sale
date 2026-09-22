<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { ProductImageView, SkuStockView } from '~/types/api'

/** 到貨通知的管理頁。訂閱只能在缺貨的商品頁按，少了這一頁就沒有地方看自己在等什麼、也取消不了。 */
const auth = useAuthStore()
const { request } = useApi()
const toast = useToast()

interface SkuLookup {
  skuId: number
  productId: number
  productName: string
  specDisplay: string
  price: number
  purchasable: boolean
}

const items = ref<SkuLookup[]>([])
const stock = ref<Record<number, SkuStockView>>({})
const images = ref<Record<number, ProductImageView>>({})
const loading = ref(false)
const error = ref<string | null>(null)
const removing = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const skuIds = await request<number[]>('/api/v1/restock-alerts', { authenticated: true })
    if (skuIds.length === 0) {
      items.value = []
      return
    }
    const ids = skuIds.join(',')
    // 後端只回 SKU 編號。名稱與價格、現在有沒有貨各問一次，都是批次端點
    const [lookups, stockRows] = await Promise.all([
      request<SkuLookup[]>(`/api/v1/catalog/skus?ids=${ids}`),
      request<SkuStockView[]>(`/api/v1/catalog/stock?skuIds=${ids}`).catch(() => []),
    ])
    items.value = lookups
    stock.value = Object.fromEntries(stockRows.map((row) => [row.skuId, row]))
    void loadImages(lookups.map((item) => item.productId))
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入到貨通知')
  } finally {
    loading.value = false
  }
}

async function loadImages(productIds: number[]) {
  try {
    images.value = await request<Record<number, ProductImageView>>(
      `/api/v1/catalog/products/images?productIds=${[...new Set(productIds)].join(',')}`)
  } catch {
    images.value = {}
  }
}

async function remove(item: SkuLookup) {
  removing.value = item.skuId
  try {
    await request<void>(`/api/v1/restock-alerts/${item.skuId}`, { method: 'DELETE', authenticated: true })
    items.value = items.value.filter((other) => other.skuId !== item.skuId)
    toast.success('已取消到貨通知')
  } catch (cause) {
    toast.error(errorMessage(cause, '取消失敗'))
  } finally {
    removing.value = null
  }
}

/** 查不到庫存就當成還在等——那是「不知道」，不可以說成「到貨了」。 */
function arrived(item: SkuLookup): boolean {
  return item.purchasable && stock.value[item.skuId]?.inStock === true
}

/** 到貨的排前面：那是使用者現在可以行動的。 */
const sorted = computed(() =>
  [...items.value].sort((a, b) => Number(arrived(b)) - Number(arrived(a))))

onMounted(() => {
  if (auth.isAuthenticated) {
    void load()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    void load()
  }
})

const { seo } = useSeo()
seo({ title: '到貨通知', noindex: true })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Restock alerts"
      title="到貨通知"
      description="商品補貨時會發站內通知給你。到貨不代表保留，熱門商品請盡快下單。"
    />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <div v-if="loading" class="flex flex-col gap-3">
        <SkeletonCard v-for="n in 3" :key="n" variant="row" />
      </div>

      <p
        v-else-if="error"
        class="rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>

      <ul v-else-if="sorted.length > 0" class="flex flex-col gap-3">
        <li v-for="item in sorted" :key="item.skuId">
          <AppCard class="flex items-center gap-4 p-4" :highlighted="arrived(item)">
            <NuxtLink :to="`/products/${item.productId}`" class="w-20 shrink-0 sm:w-24">
              <ProductTile
                :seed="item.productId" :label="item.productName"
                :src="images[item.productId]?.thumbUrl ?? null" class="rounded-sm"
              />
            </NuxtLink>

            <div class="min-w-0 flex-1">
              <NuxtLink
                :to="`/products/${item.productId}`"
                class="line-clamp-2 font-medium leading-snug transition-colors hover:text-accent"
              >
                {{ item.productName }}
              </NuxtLink>
              <p class="mt-1 truncate text-sm text-ink-muted">{{ item.specDisplay }}</p>
              <div class="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1">
                <MoneyText :amount="item.price" tone="danger" />
                <span
                  class="inline-flex items-center gap-1 rounded-sm border px-2 py-0.5 text-xs font-medium"
                  :class="arrived(item)
                    ? 'border-ok/40 bg-ok-soft text-ok'
                    : 'border-line bg-sunken text-ink-muted'"
                >
                  {{ arrived(item) ? '已到貨' : (item.purchasable ? '等待到貨' : '商品已下架') }}
                </span>
              </div>
            </div>

            <div class="flex shrink-0 flex-col items-stretch gap-2">
              <AppButton v-if="arrived(item)" size="sm" @click="navigateTo(`/products/${item.productId}`)">
                去購買
              </AppButton>
              <AppButton
                variant="ghost" size="sm" :disabled="removing === item.skuId"
                @click="remove(item)"
              >
                {{ removing === item.skuId ? '取消中⋯' : '取消通知' }}
              </AppButton>
            </div>
          </AppCard>
        </li>
      </ul>

      <EmptyState
        v-else
        title="目前沒有在等的商品。"
        hint="遇到售完的商品時，按「有貨通知我」，補貨後會第一時間告訴你。"
      >
        <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
          去逛商品
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
