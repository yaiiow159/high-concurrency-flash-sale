<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ApiResponse, CategoryView, ProductView } from '~/types/api'

/**
 * 商品管理。
 *
 * **後台看得到草稿與已下架的商品**，前台看不到。看不到草稿的話，
 * 剛建好的商品就找不到入口去上架它——而那正是「建立與上架分開」
 * 這個設計最容易被做壞的地方。
 *
 * 上架會把商品寫進搜尋索引、下架會移除它。這兩個動作在畫面上要看得出
 * 「會不會影響顧客看得到的東西」，因此下架用 secondary 而非 danger：
 * 它不刪資料，歷史訂單仍然查得到。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { products, putOnShelf, takeOffShelf, createProduct } = useAdmin()

const TABS = [
  { value: '', label: '全部' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'ON_SHELF', label: '已上架' },
  { value: 'OFF_SHELF', label: '已下架' },
] as const

const tab = ref<string>('')
const rows = ref<ProductView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<number | null>(null)
const creating = ref(false)

/** 類目選項。建立商品需要它，而類目幾乎不變，載入一次就夠。 */
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')
const categoryOptions = computed(() =>
  (categoryData.value?.data ?? []).flatMap((parent) => [
    { id: parent.categoryId, label: parent.name },
    ...parent.children.map((child) => ({ id: child.categoryId, label: `　${child.name}` })),
  ]))

/**
 * 分頁。
 *
 * **後台用頁碼，商店用游標**——這不是重要性的差異，是存取方式的差異：
 * 維運要能直接跳到第 N 頁核對，而 keyset 做不到跳頁（ADR-0021 決策 4）。
 *
 * 沒有總筆數可以顯示「共 N 頁」——那需要一次 COUNT(*)，
 * 在 5 萬列上比查詢本身還貴。改用「這一頁滿了就可能還有下一頁」判斷。
 */
const PAGE_SIZE = 20
const page = ref(0)
const hasNextPage = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    // 多要一筆來判斷還有沒有下一頁，回傳時砍掉
    const batch = await products(tab.value, page.value, PAGE_SIZE + 1)
    hasNextPage.value = batch.length > PAGE_SIZE
    rows.value = hasNextPage.value ? batch.slice(0, PAGE_SIZE) : batch
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入商品清單')
    rows.value = []
    hasNextPage.value = false
  } finally {
    loading.value = false
  }
}

function goToPage(next: number) {
  page.value = Math.max(0, next)
  void load()
}

// 換分頁標籤要回到第一頁——留在第 7 頁看另一個狀態的商品，
// 很可能是一片空白，而使用者會以為那個狀態沒有商品
watch(tab, () => { page.value = 0 })

async function toggleShelf(product: ProductView) {
  const goingOnline = product.status !== 'ON_SHELF'
  if (!goingOnline
    && !confirm(`下架「${product.name}」？\n\n它會從商店與搜尋結果移除，但資料不會被刪除。`)) {
    return
  }
  busy.value = product.productId
  error.value = null
  try {
    await (goingOnline ? putOnShelf(product.productId) : takeOffShelf(product.productId))
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '操作失敗')
  } finally {
    busy.value = null
  }
}

async function onCreated(payload: unknown) {
  error.value = null
  try {
    await createProduct(payload)
    creating.value = false
    // 切到草稿分頁——新建的商品就在那裡，而使用者的下一個動作通常是上架它
    tab.value = 'DRAFT'
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '建立商品失敗')
  }
}

watch(tab, load)
onMounted(load)

useHead({ title: '商品管理' })
</script>

<template>
  <div>
    <AdminPageHeader title="商品管理" description="建立商品、上下架；上架會同步寫進搜尋索引">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
        <AppButton size="sm" @click="creating = !creating">
          {{ creating ? '收起表單' : '新增商品' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <AdminProductForm
      v-if="creating"
      class="mb-6"
      :categories="categoryOptions"
      @submit="onCreated"
      @cancel="creating = false"
    />

    <AdminTabs v-model="tab" :tabs="TABS" />

    <p
      v-if="error"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="loading" class="mt-4 flex flex-col gap-2">
      <SkeletonBlock class="h-20" />
      <SkeletonBlock class="h-20" />
    </div>

    <EmptyState v-else-if="rows.length === 0" class="mt-6" title="這個狀態下沒有商品。" />

    <ul v-else class="mt-4 flex flex-col gap-2">
      <li v-for="product in rows" :key="product.productId">
        <AppCard class="flex flex-wrap items-center gap-x-5 gap-y-3 p-4">
          <ProductTile
            :seed="product.productId" :label="product.name"
            class="h-14 w-14 shrink-0 rounded-sm"
          />

          <div class="min-w-0 flex-1">
            <div class="flex flex-wrap items-center gap-2">
              <span class="truncate text-sm font-medium">{{ product.name }}</span>
              <StatusBadge :status="product.status" />
            </div>
            <p class="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-ink-muted">
              <span v-if="product.brand">{{ product.brand }}</span>
              <span class="figure">{{ product.skus.length }} 個規格</span>
              <span class="figure">最低 NT$ {{ product.lowestPrice?.toLocaleString() ?? '—' }}</span>
            </p>
          </div>

          <div class="flex shrink-0 items-center gap-2">
            <NuxtLink
              v-if="product.status === 'ON_SHELF'"
              :to="`/products/${product.productId}`"
              class="rounded-sm px-2 py-1.5 text-xs text-ink-muted transition-colors hover:text-accent"
            >
              前台檢視 →
            </NuxtLink>
            <AppButton
              :variant="product.status === 'ON_SHELF' ? 'secondary' : 'primary'"
              size="sm"
              :disabled="busy === product.productId"
              @click="toggleShelf(product)"
            >
              {{ product.status === 'ON_SHELF' ? '下架' : '上架' }}
            </AppButton>
          </div>
        </AppCard>
      </li>
    </ul>

    <!--
      頁碼而不是「載入更多」：維運要能直接跳到第 N 頁核對。
      沒有總筆數可顯示——那需要一次 COUNT(*)，在 5 萬列上比查詢本身還貴
    -->
    <div
      v-if="rows.length > 0 && (page > 0 || hasNextPage)"
      class="mt-6 flex items-center justify-center gap-3"
    >
      <AppButton
        variant="secondary" size="sm"
        :disabled="page === 0 || loading"
        @click="goToPage(page - 1)"
      >
        上一頁
      </AppButton>
      <span class="figure text-sm text-ink-muted">第 {{ page + 1 }} 頁</span>
      <AppButton
        variant="secondary" size="sm"
        :disabled="!hasNextPage || loading"
        @click="goToPage(page + 1)"
      >
        下一頁
      </AppButton>
    </div>
  </div>
</template>
