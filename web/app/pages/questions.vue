<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { ProductView, QuestionView } from '~/types/api'

/** 我的提問。問了問題的人需要一個地方知道「回答了沒」，否則只能一件件商品回去翻。 */
const auth = useAuthStore()
const { request } = useApi()

const PAGE_SIZE = 20

const questions = ref<QuestionView[]>([])
const products = ref<Record<number, ProductView>>({})
const loading = ref(false)
const loadingMore = ref(false)
const error = ref<string | null>(null)
const page = ref(0)
const reachedEnd = ref(false)

async function load(reset = true) {
  if (reset) {
    loading.value = true
    page.value = 0
    reachedEnd.value = false
  } else {
    loadingMore.value = true
  }
  error.value = null
  try {
    const batch = await request<QuestionView[]>(
      `/api/v1/questions/mine?page=${page.value}&size=${PAGE_SIZE}`, { authenticated: true })
    questions.value = reset ? batch : [...questions.value, ...batch]
    reachedEnd.value = batch.length < PAGE_SIZE
    void loadProducts(batch)
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入提問')
    if (!reset) {
      page.value -= 1
    }
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

/** 提問只帶 productId。商品名另外補，補不到就退回「查看商品」——問題本身才是這一頁的主角。 */
async function loadProducts(batch: QuestionView[]) {
  const missing = [...new Set(batch.map((question) => question.productId))]
    .filter((id) => !(id in products.value))
  const found = await Promise.all(missing.map((id) =>
    request<ProductView>(`/api/v1/catalog/products/${id}`).catch(() => null)))
  const next = { ...products.value }
  found.forEach((product) => {
    if (product) {
      next[product.productId] = product
    }
  })
  products.value = next
}

async function loadMore() {
  page.value += 1
  await load(false)
}

function statusOf(question: QuestionView): { label: string, tone: 'ok' | 'neutral' } {
  if (question.answer) {
    return { label: '已回覆', tone: 'ok' }
  }
  return { label: '等待回覆', tone: 'neutral' }
}

function formatDate(value: string | undefined): string {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleDateString('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

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
seo({ title: '我的提問', noindex: true })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Questions"
      title="我的提問"
      description="商家回覆後問題才會公開在商品頁；還沒回覆的只有你自己看得到。"
    />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <div v-if="loading" class="flex flex-col gap-3">
        <SkeletonCard v-for="n in 3" :key="n" variant="row" />
      </div>

      <p
        v-else-if="error && questions.length === 0"
        class="rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>

      <template v-else-if="questions.length > 0">
        <ul class="flex flex-col gap-3">
          <li v-for="question in questions" :key="question.questionId">
            <AppCard class="p-5">
              <div class="flex flex-wrap items-center justify-between gap-2">
                <NuxtLink
                  :to="`/products/${question.productId}`"
                  class="min-w-0 truncate text-sm font-medium text-ink-muted transition-colors hover:text-accent"
                >
                  {{ products[question.productId]?.name ?? '查看商品' }} →
                </NuxtLink>
                <span
                  class="inline-flex shrink-0 items-center rounded-sm border px-2 py-0.5 text-xs font-medium"
                  :class="statusOf(question).tone === 'ok'
                    ? 'border-ok/40 bg-ok-soft text-ok'
                    : 'border-line bg-sunken text-ink-muted'"
                >
                  {{ statusOf(question).label }}
                </span>
              </div>

              <div class="mt-4 flex gap-3">
                <span
                  class="grid h-6 w-6 shrink-0 place-items-center rounded-sm bg-accent text-xs font-extrabold text-white"
                  aria-hidden="true"
                >
                  問
                </span>
                <div class="min-w-0">
                  <p class="whitespace-pre-line leading-relaxed">{{ question.content }}</p>
                  <p class="figure mt-1 text-xs text-ink-faint">{{ formatDate(question.createdAt) }}</p>
                </div>
              </div>

              <div v-if="question.answer" class="mt-4 flex gap-3 rounded-sm bg-sunken p-3.5">
                <span
                  class="grid h-6 w-6 shrink-0 place-items-center rounded-sm bg-ink-inverse text-xs font-extrabold text-white"
                  aria-hidden="true"
                >
                  答
                </span>
                <div class="min-w-0">
                  <p class="whitespace-pre-line text-sm leading-relaxed text-ink-muted">{{ question.answer }}</p>
                  <p class="figure mt-1 text-xs text-ink-faint">商家回覆於 {{ formatDate(question.answeredAt) }}</p>
                </div>
              </div>
            </AppCard>
          </li>
        </ul>

        <p v-if="error" class="mt-4 text-center text-sm text-danger" role="alert">{{ error }}</p>
        <div v-if="!reachedEnd" class="mt-6 flex justify-center">
          <AppButton variant="secondary" :disabled="loadingMore" @click="loadMore">
            {{ loadingMore ? '載入中⋯' : '載入更多' }}
          </AppButton>
        </div>
      </template>

      <EmptyState
        v-else
        title="還沒有問過任何問題。"
        hint="對商品有疑問時，在商品頁的「商品問答」直接發問，商家回覆後會出現在這裡。"
      >
        <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
          去逛商品
        </AppButton>
      </EmptyState>
    </template>
  </div>
</template>
