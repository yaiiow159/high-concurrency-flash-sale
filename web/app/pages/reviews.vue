<script setup lang="ts">
import { useReviews } from '~/composables/useReviews'
import { useAuthStore } from '~/stores/auth'
import type { ReviewView } from '~/types/api'

/**
 * 我寫過的評價，可就地修改。
 *
 * **就地編輯而不是跳到另一頁。** 使用者來這裡的動機通常是
 * 「補一句話」或「用了幾天想改分數」，那是一個小動作；
 * 為它換一次頁、再導回來，中間還得記住自己剛才在第幾則。
 *
 * 修改窗口是否還開著由**伺服器**判斷（`editable`），前端不自己拿
 * createdAt 算七天——兩邊算出來的結果會在時區與時鐘偏移上分岔，
 * 而那表現成「畫面顯示可以改，送出卻被拒絕」。
 */
const auth = useAuthStore()
const { mine, edit } = useReviews()

const reviews = ref<ReviewView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

/** 正在編輯哪一則；null 代表沒有。 */
const editingId = ref<number | null>(null)
const draftStars = ref(0)
const draftContent = ref('')
const saving = ref(false)

const MAX_LENGTH = 1000

async function load() {
  loading.value = true
  try {
    reviews.value = await mine()
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '無法載入評價'
  } finally {
    loading.value = false
  }
}

function startEdit(review: ReviewView) {
  editingId.value = review.reviewId
  draftStars.value = review.stars
  draftContent.value = review.content
  error.value = null
}

function cancelEdit() {
  editingId.value = null
}

async function save() {
  if (editingId.value === null || draftStars.value === 0 || !draftContent.value.trim()) {
    return
  }
  saving.value = true
  error.value = null
  try {
    const updated = await edit(editingId.value, {
      stars: draftStars.value,
      content: draftContent.value.trim(),
    })
    // 就地換掉那一則，不重打整份清單——重打會讓畫面跳回頂端
    reviews.value = reviews.value.map(
      (review) => review.reviewId === updated.reviewId ? updated : review)
    editingId.value = null
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '修改失敗'
  } finally {
    saving.value = false
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

useHead({ title: '我的評價' })
</script>

<template>
  <div>
    <PageHeader eyebrow="Reviews" title="我的評價" />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div v-else-if="loading" class="flex flex-col gap-4">
      <SkeletonCard variant="row" />
      <SkeletonCard variant="row" />
    </div>

    <EmptyState
      v-else-if="reviews.length === 0"
      title="還沒有寫過評價。"
      hint="訂單送達之後，可以在訂單頁分享你的使用心得。"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo('/orders')">
        看我的訂單
      </AppButton>
    </EmptyState>

    <div v-else class="max-w-3xl">
      <p
        v-if="error"
        class="mb-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>

      <ul class="flex flex-col gap-3">
        <li v-for="review in reviews" :key="review.reviewId">
          <AppCard class="px-5">
            <!-- 編輯中：就地換成表單，位置不動 -->
            <div v-if="editingId === review.reviewId" class="py-5">
              <StarRating
                v-model="draftStars" interactive size="lg"
                :name="`stars-${review.reviewId}`"
              />
              <textarea
                v-model="draftContent"
                rows="5"
                :maxlength="MAX_LENGTH"
                class="mt-4 w-full resize-y rounded-sm border border-line bg-surface p-3.5
                       text-sm leading-relaxed transition-colors focus:border-line-strong"
              />
              <div class="mt-3 flex items-center justify-end gap-2">
                <AppButton variant="secondary" size="sm" @click="cancelEdit">
                  取消
                </AppButton>
                <AppButton
                  size="sm"
                  :disabled="saving || draftStars === 0 || !draftContent.trim()"
                  @click="save"
                >
                  {{ saving ? '儲存中⋯' : '儲存修改' }}
                </AppButton>
              </div>
            </div>

            <ReviewCard v-else :review="review" show-edit @edit="startEdit" />
          </AppCard>
        </li>
      </ul>
    </div>
  </div>
</template>
