<script setup lang="ts">
import type {
  CarouselSlideConfig, HomeProductSource, HomeSectionConfig, HomeSectionType,
} from '~/types/api'

definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { request } = useApi()
const { uploadObject, errorMessage } = useProductMedia()

const sections = ref<HomeSectionConfig[]>([])
const slides = ref<CarouselSlideConfig[]>([])
const loading = ref(true)
const message = ref('')
const error = ref('')

const TYPE_LABELS: Record<HomeSectionType, string> = {
  CAROUSEL: '輪播圖',
  FLASH_SALE: '限時搶購',
  CATEGORY_GRID: '分類入口',
  PRODUCT_RAIL: '商品版位',
}

const SOURCE_LABELS: Record<HomeProductSource, string> = {
  BEST_SELLING: '熱銷排行',
  NEWEST: '最新上架',
  TOP_RATED: '評分最高',
  CATEGORY: '指定類目',
  CURATED: '人工選品',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [sectionList, slideList] = await Promise.all([
      request<HomeSectionConfig[]>('/api/v1/admin/home/sections', { authenticated: true }),
      request<CarouselSlideConfig[]>('/api/v1/admin/home/slides', { authenticated: true }),
    ])
    sections.value = sectionList
    slides.value = slideList
  } catch (cause) {
    error.value = errorMessage(cause, '讀取版型失敗')
  } finally {
    loading.value = false
  }
}

/** 每次操作後重新載入而不是就地改本地狀態：排序與可見性由後端決定，本地推算遲早會不一致。 */
async function run(action: () => Promise<unknown>, done: string) {
  message.value = ''
  error.value = ''
  try {
    await action()
    message.value = done
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '操作失敗')
  }
}

function saveSection(section: HomeSectionConfig) {
  return run(() => request(`/api/v1/admin/home/sections/${section.sectionId}`, {
    method: 'PUT',
    authenticated: true,
    body: {
      type: section.type,
      title: section.title,
      subtitle: section.subtitle || null,
      source: section.source || null,
      categoryId: section.categoryId || null,
      productIds: section.productIds ?? [],
      itemLimit: section.itemLimit,
      sortOrder: section.sortOrder,
      enabled: section.enabled,
      visibleFrom: section.visibleFrom || null,
      visibleTo: section.visibleTo || null,
    },
  }), '版位已更新')
}

function deleteSection(section: HomeSectionConfig) {
  if (!confirm(`確定刪除版位「${section.title}」？`)) {
    return
  }
  return run(() => request(`/api/v1/admin/home/sections/${section.sectionId}`,
    { method: 'DELETE', authenticated: true }), '版位已刪除')
}

const newSection = reactive({
  type: 'PRODUCT_RAIL' as HomeSectionType,
  title: '',
  subtitle: '',
  source: 'BEST_SELLING' as HomeProductSource,
  categoryId: '' as string | number,
  productIds: '',
  itemLimit: 8,
  sortOrder: 10,
  visibleFrom: '',
  visibleTo: '',
})

function createSection() {
  const ids = newSection.productIds
    .split(',')
    .map((part) => Number(part.trim()))
    .filter((id) => Number.isFinite(id) && id > 0)

  return run(() => request('/api/v1/admin/home/sections', {
    method: 'POST',
    authenticated: true,
    body: {
      type: newSection.type,
      title: newSection.title,
      subtitle: newSection.subtitle || null,
      source: newSection.type === 'PRODUCT_RAIL' ? newSection.source : null,
      categoryId: newSection.categoryId ? Number(newSection.categoryId) : null,
      productIds: ids,
      itemLimit: newSection.itemLimit,
      sortOrder: newSection.sortOrder,
      enabled: true,
      visibleFrom: newSection.visibleFrom ? new Date(newSection.visibleFrom).toISOString() : null,
      visibleTo: newSection.visibleTo ? new Date(newSection.visibleTo).toISOString() : null,
    },
  }), '版位已新增').then(() => {
    newSection.title = ''
    newSection.subtitle = ''
    newSection.productIds = ''
  })
}

const uploading = ref(false)

async function addSlide(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) {
    return
  }
  uploading.value = true
  try {
    const objectKey = await uploadObject(file)
    await run(() => request('/api/v1/admin/home/slides', {
      method: 'POST',
      authenticated: true,
      body: {
        objectKey,
        title: '',
        linkUrl: '',
        sortOrder: slides.value.length,
        enabled: true,
        visibleFrom: null,
        visibleTo: null,
      },
    }), '輪播圖已新增')
  } catch (cause) {
    error.value = errorMessage(cause, '上傳失敗')
  } finally {
    uploading.value = false
    input.value = ''
  }
}

function saveSlide(slide: CarouselSlideConfig) {
  return run(() => request(`/api/v1/admin/home/slides/${slide.slideId}`, {
    method: 'PUT',
    authenticated: true,
    body: {
      objectKey: slide.objectKey,
      title: slide.title || null,
      linkUrl: slide.linkUrl || null,
      sortOrder: slide.sortOrder,
      enabled: slide.enabled,
      visibleFrom: slide.visibleFrom || null,
      visibleTo: slide.visibleTo || null,
    },
  }), '輪播圖已更新')
}

function deleteSlide(slide: CarouselSlideConfig) {
  if (!confirm('確定刪除這張輪播圖？')) {
    return
  }
  return run(() => request(`/api/v1/admin/home/slides/${slide.slideId}`,
    { method: 'DELETE', authenticated: true }), '輪播圖已刪除')
}

onMounted(load)
useHead({ title: '首頁版型 — 後台' })
</script>

<template>
  <div class="flex flex-col gap-8">
    <AdminPageHeader
      title="首頁版型"
      description="版位順序、標題與上架期間都在這裡設定，前台首頁照這份設定畫。"
    />

    <p v-if="message" class="rounded-sm bg-ok-soft px-3 py-2 text-sm text-ok">
      {{ message }}
    </p>
    <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger">
      {{ error }}
    </p>
    <p class="text-xs text-ink-faint">
      首頁有 60 秒的 ISR 快取，改完最多要等一分鐘才會在前台看到。
    </p>

    <section>
      <h2 class="mb-3 text-lg font-semibold">輪播圖</h2>

      <div class="mb-4">
        <label
          class="inline-flex cursor-pointer items-center gap-2 rounded-sm border border-line
                 bg-surface px-3 py-2 text-sm shadow-rest hover:border-accent"
        >
          <input type="file" accept="image/*" class="hidden" @change="addSlide">
          {{ uploading ? '上傳中…' : '＋ 上傳輪播圖' }}
        </label>
      </div>

      <ul v-if="slides.length > 0" class="flex flex-col gap-3">
        <li
          v-for="slide in slides"
          :key="slide.slideId"
          class="flex flex-col gap-3 rounded-sm border border-line bg-surface p-3 shadow-rest
                 sm:flex-row sm:items-center"
        >
          <div class="h-16 w-28 shrink-0 overflow-hidden rounded-sm bg-sunken">
            <img :src="slide.imageUrl" alt="" class="h-full w-full object-cover">
          </div>
          <div class="grid flex-1 gap-2 sm:grid-cols-4">
            <input v-model="slide.title" class="field" placeholder="標題（可留空）">
            <input v-model="slide.linkUrl" class="field" placeholder="連結，例如 /products/1">
            <input v-model.number="slide.sortOrder" type="number" class="field" placeholder="排序">
            <label class="flex items-center gap-2 text-sm">
              <input v-model="slide.enabled" type="checkbox"> 啟用
            </label>
          </div>
          <div class="flex shrink-0 gap-2">
            <AppButton size="sm" variant="secondary" @click="saveSlide(slide)">儲存</AppButton>
            <AppButton size="sm" variant="ghost" @click="deleteSlide(slide)">刪除</AppButton>
          </div>
        </li>
      </ul>
      <EmptyState v-else-if="!loading" title="還沒有輪播圖。" />
    </section>

    <section>
      <h2 class="mb-3 text-lg font-semibold">版位</h2>

      <ul class="flex flex-col gap-3">
        <li
          v-for="section in sections"
          :key="section.sectionId"
          class="rounded-sm border border-line bg-surface p-3 shadow-rest"
        >
          <div class="mb-2 flex items-center gap-2">
            <span
              class="inline-flex rounded-sm border border-line bg-sunken px-2 py-0.5
                     text-xs text-ink-muted"
            >{{ TYPE_LABELS[section.type] }}</span>
            <span v-if="section.source" class="text-xs text-ink-faint">
              {{ SOURCE_LABELS[section.source] }}
            </span>
          </div>
          <div class="grid gap-2 sm:grid-cols-5">
            <input v-model="section.title" class="field" placeholder="標題">
            <input v-model="section.subtitle" class="field" placeholder="副標（可留空）">
            <input
              v-model.number="section.itemLimit" type="number" min="1" max="20"
              class="field" placeholder="商品數"
            >
            <input v-model.number="section.sortOrder" type="number" class="field" placeholder="排序">
            <label class="flex items-center gap-2 text-sm">
              <input v-model="section.enabled" type="checkbox"> 啟用
            </label>
          </div>
          <div class="mt-2 grid gap-2 sm:grid-cols-2">
            <label class="text-xs text-ink-faint">
              上架時間
              <input v-model="section.visibleFrom" type="datetime-local" class="field mt-1">
            </label>
            <label class="text-xs text-ink-faint">
              下架時間
              <input v-model="section.visibleTo" type="datetime-local" class="field mt-1">
            </label>
          </div>
          <div class="mt-3 flex gap-2">
            <AppButton size="sm" variant="secondary" @click="saveSection(section)">儲存</AppButton>
            <AppButton size="sm" variant="ghost" @click="deleteSection(section)">刪除</AppButton>
          </div>
        </li>
      </ul>
    </section>

    <section>
      <h2 class="mb-3 text-lg font-semibold">新增版位</h2>
      <div class="grid gap-2 rounded-sm border border-line bg-surface p-3 shadow-rest sm:grid-cols-3">
        <label class="text-xs text-ink-faint">
          類型
          <select v-model="newSection.type" class="field mt-1">
            <option v-for="(label, value) in TYPE_LABELS" :key="value" :value="value">
              {{ label }}
            </option>
          </select>
        </label>
        <label v-if="newSection.type === 'PRODUCT_RAIL'" class="text-xs text-ink-faint">
          內容來源
          <select v-model="newSection.source" class="field mt-1">
            <option v-for="(label, value) in SOURCE_LABELS" :key="value" :value="value">
              {{ label }}
            </option>
          </select>
        </label>
        <label class="text-xs text-ink-faint">
          標題
          <input v-model="newSection.title" class="field mt-1" placeholder="例如：當季限定">
        </label>
        <label class="text-xs text-ink-faint">
          副標
          <input v-model="newSection.subtitle" class="field mt-1">
        </label>
        <label v-if="newSection.source === 'CATEGORY'" class="text-xs text-ink-faint">
          類目 ID
          <input v-model="newSection.categoryId" type="number" class="field mt-1">
        </label>
        <label v-if="newSection.source === 'CURATED'" class="text-xs text-ink-faint">
          商品 ID（逗號分隔，順序即顯示順序）
          <input v-model="newSection.productIds" class="field mt-1" placeholder="1, 2, 3">
        </label>
        <label class="text-xs text-ink-faint">
          商品數
          <input v-model.number="newSection.itemLimit" type="number" min="1" max="20" class="field mt-1">
        </label>
        <label class="text-xs text-ink-faint">
          排序
          <input v-model.number="newSection.sortOrder" type="number" class="field mt-1">
        </label>
        <label class="text-xs text-ink-faint">
          上架時間
          <input v-model="newSection.visibleFrom" type="datetime-local" class="field mt-1">
        </label>
        <label class="text-xs text-ink-faint">
          下架時間
          <input v-model="newSection.visibleTo" type="datetime-local" class="field mt-1">
        </label>
        <div class="flex items-end">
          <AppButton size="sm" :disabled="!newSection.title" @click="createSection">
            新增版位
          </AppButton>
        </div>
      </div>
    </section>
  </div>
</template>
