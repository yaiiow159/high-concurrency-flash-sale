<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ApiResponse, CategoryView, ProductImageView, ProductView } from '~/types/api'

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

/**
 * 圖片上傳（ADR-0027）。
 *
 * 位元組直傳物件儲存，不經過應用伺服器——那條請求執行緒是秒殺熱路徑要用的。
 */
const { upload, remove, listImages } = useProductMedia()
const images = ref<Record<number, ProductImageView[]>>({})
const uploading = ref<number | null>(null)

async function loadImagesFor(productId: number) {
  try {
    images.value = { ...images.value, [productId]: await listImages(productId) }
  } catch {
    images.value = { ...images.value, [productId]: [] }
  }
}

/** 換頁或換分類之後，把這一頁商品的圖片一次帶回來。 */
watch(rows, (list) => {
  list.forEach((product) => void loadImagesFor(product.productId))
})

async function onPickFile(productId: number, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 清掉 value，否則選同一個檔案第二次不會觸發 change
  input.value = ''
  if (!file) {
    return
  }
  uploading.value = productId
  error.value = null
  try {
    await upload(productId, file)
    await loadImagesFor(productId)
  } catch (cause) {
    error.value = errorMessage(cause, '圖片上傳失敗')
  } finally {
    uploading.value = null
  }
}

async function onRemoveImage(productId: number, imageId: number) {
  // 只解除掛載，物件保留交由對帳處理（ADR-0027 決策 5）——
  // 講清楚，否則維運會以為檔案被刪了
  if (!confirm('取消掛載這張圖？' + String.fromCharCode(10, 10)
    + '檔案本身會保留，由對帳流程處理。')) {
    return
  }
  try {
    await remove(productId, imageId)
    await loadImagesFor(productId)
  } catch (cause) {
    error.value = errorMessage(cause, '取消掛載失敗')
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
            :src="images[product.productId]?.[0]?.thumbUrl ?? null"
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

          <!-- 已掛載的圖片；點縮圖可以取消掛載 -->
          <ul v-if="images[product.productId]?.length" class="flex shrink-0 gap-1.5">
            <li v-for="image in images[product.productId]" :key="image.imageId">
              <button
                type="button"
                class="h-10 w-10 overflow-hidden rounded-sm border border-line
                       transition-colors hover:border-danger"
                :title="`取消掛載`"
                @click="onRemoveImage(product.productId, image.imageId)"
              >
                <img :src="image.thumbUrl" alt="" class="h-full w-full object-cover">
              </button>
            </li>
          </ul>

          <div class="flex shrink-0 items-center gap-2">
            <label
              class="cursor-pointer rounded-sm border border-line-strong bg-surface px-3 py-1.5
                     text-xs transition-colors hover:border-cta hover:text-cta"
            >
              {{ uploading === product.productId ? '上傳中⋯' : '加圖片' }}
              <input
                type="file"
                accept="image/jpeg,image/png,image/webp"
                class="sr-only"
                :disabled="uploading === product.productId"
                @change="onPickFile(product.productId, $event)"
              >
            </label>
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
