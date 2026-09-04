<script setup lang="ts">
import { useReviews } from '~/composables/useReviews'
import { useAuthStore } from '~/stores/auth'
import type { ReviewableView } from '~/types/api'

/**
 * 撰寫評價。
 *
 * **可評項目由後端算**（`/reviewable`），前端不自己比對訂單行與既有評價——
 * 前端再實作一次的話，症狀會是「畫面說可以評，送出卻被拒絕」。
 *
 * 一次只評一項。做成「一次評完整張訂單」看起來省事，
 * 但那會讓其中一項失敗時整個表單卡住，而使用者不知道是哪一項有問題。
 * 逐項送出則每一項的成敗都是獨立的，失敗的那一項還留在清單上可以重試。
 */
const route = useRoute()
const orderNo = route.params.orderNo as string

const auth = useAuthStore()
const { reviewable, write } = useReviews()

const view = ref<ReviewableView | null>(null)
const loading = ref(true)
const selectedSkuId = ref<number | null>(null)
const stars = ref(0)
const content = ref('')
const submitting = ref(false)
const error = ref<string | null>(null)
/** 這次已經評完的項目，用來即時更新清單而不必重打一次 API。 */
const justSubmitted = ref<number[]>([])

const pendingLines = computed(() =>
  (view.value?.lines ?? []).filter(
    (line) => line.pending && !justSubmitted.value.includes(line.skuId)),
)

const canSubmit = computed(() =>
  selectedSkuId.value !== null
  && stars.value > 0
  && content.value.trim().length > 0
  && !submitting.value)

const MAX_LENGTH = 1000
const remaining = computed(() => MAX_LENGTH - content.value.length)

async function load() {
  loading.value = true
  try {
    view.value = await reviewable(orderNo)
    // 只有一項待評時直接選起來——多按一次沒有帶來任何選擇
    if (pendingLines.value.length === 1) {
      selectedSkuId.value = pendingLines.value[0]!.skuId
    }
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '無法載入可評價項目'
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (!canSubmit.value || selectedSkuId.value === null) {
    return
  }
  submitting.value = true
  error.value = null
  try {
    await write(orderNo, {
      skuId: selectedSkuId.value,
      stars: stars.value,
      content: content.value.trim(),
    })
    justSubmitted.value = [...justSubmitted.value, selectedSkuId.value]
    // 清空表單讓使用者接著評下一項，而不是把他丟回訂單頁再走一次
    selectedSkuId.value = pendingLines.value[0]?.skuId ?? null
    stars.value = 0
    content.value = ''
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '發表評價失敗'
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (auth.isAuthenticated) {
    void load()
  }
})
watch(() => auth.isAuthenticated, (authenticated) => {
  if (authenticated) {
    void load()
  }
})

useHead({ title: '撰寫評價' })
</script>

<template>
  <div>
    <PageHeader eyebrow="Review" title="撰寫評價">
      <template #actions>
        <NuxtLink
          :to="`/orders/${orderNo}`"
          class="text-sm text-ink-muted transition-colors hover:text-ink"
        >
          ← 回訂單
        </NuxtLink>
      </template>
    </PageHeader>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <SkeletonCard v-else-if="loading" />

    <!-- 不能評價時說明原因，不是給一張空表單 -->
    <EmptyState
      v-else-if="view && !view.reviewable && pendingLines.length === 0"
      title="這張訂單沒有可以評價的項目。"
      :hint="view.reason ?? undefined"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo(`/orders/${orderNo}`)">
        回訂單
      </AppButton>
    </EmptyState>

    <EmptyState
      v-else-if="pendingLines.length === 0"
      title="這張訂單的商品都評價完了。"
      hint="謝謝你的分享——其他人買之前會看到它。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/reviews')">
        看我寫過的評價
      </AppButton>
    </EmptyState>

    <div v-else class="grid max-w-3xl gap-8">
      <!-- 多項待評時才需要選；只有一項時上面已經自動選好 -->
      <section v-if="pendingLines.length > 1" aria-labelledby="item-heading">
        <h2 id="item-heading" class="eyebrow mb-3">要評價哪一項</h2>
        <div class="flex flex-col gap-2">
          <label
            v-for="line in pendingLines"
            :key="line.skuId"
            class="flex cursor-pointer items-center gap-3 rounded-sm border p-4 text-sm
                   transition-colors"
            :class="line.skuId === selectedSkuId
              ? 'border-accent bg-accent-soft'
              : 'border-line hover:border-line-strong'"
          >
            <input
              v-model="selectedSkuId" type="radio" name="sku"
              :value="line.skuId" class="accent-[var(--accent)]"
            >
            <span>{{ line.skuSnapshot }}</span>
          </label>
        </div>
      </section>

      <AppCard class="p-5 sm:p-6">
        <p v-if="pendingLines.length === 1" class="mb-5 text-sm text-ink-muted">
          正在評價
          <span class="font-medium text-ink">{{ pendingLines[0]!.skuSnapshot }}</span>
        </p>

        <section aria-labelledby="stars-heading">
          <h2 id="stars-heading" class="eyebrow mb-3">你給幾分</h2>
          <StarRating v-model="stars" interactive size="xl" name="stars" />
        </section>

        <section class="mt-7" aria-labelledby="content-heading">
          <h2 id="content-heading" class="eyebrow mb-3">說說你的使用心得</h2>
          <textarea
            v-model="content"
            rows="6"
            :maxlength="MAX_LENGTH"
            placeholder="這件商品實際用起來如何？有什麼是買之前會想知道的？"
            class="w-full resize-y rounded-sm border border-line bg-surface p-3.5 text-sm
                   leading-relaxed transition-colors placeholder:text-ink-faint
                   focus:border-line-strong"
          />
          <!--
            字數用「還可以打幾個字」而不是「已打幾個字」——
            使用者關心的是剩餘額度，而不是自己打了多少
          -->
          <p
            class="mt-1.5 text-right text-xs"
            :class="remaining < 50 ? 'text-danger' : 'text-ink-faint'"
          >
            還可以輸入 <span class="figure">{{ remaining }}</span> 字
          </p>
        </section>

        <p
          v-if="error"
          class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
          role="alert"
        >
          {{ error }}
        </p>

        <div class="mt-6 flex items-center justify-between gap-4">
          <p class="text-xs text-ink-faint">
            發表後七天內可以修改。
          </p>
          <AppButton :disabled="!canSubmit" @click="submit">
            {{ submitting ? '發表中⋯' : '發表評價' }}
          </AppButton>
        </div>
      </AppCard>

      <p
        v-if="justSubmitted.length > 0"
        class="rounded-sm border border-ok/40 bg-ok-soft p-3 text-sm"
        :style="{ color: 'var(--ok)' }"
        role="status"
      >
        已發表 {{ justSubmitted.length }} 則評價。
      </p>
    </div>
  </div>
</template>
