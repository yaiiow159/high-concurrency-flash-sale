<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { QuestionPage, QuestionView } from '~/types/api'

/** 商品問答。任何登入者都能問——還沒買的人才有問題要問。 */
const props = defineProps<{ productId: number }>()

const { request } = useApi()
const auth = useAuthStore()

const items = ref<QuestionView[]>([])
const total = ref(0)
const loading = ref(true)
const content = ref('')
const submitting = ref(false)
const message = ref('')
const error = ref('')

async function load() {
  loading.value = true
  try {
    const page = await request<QuestionPage>(
      `/api/v1/catalog/products/${props.productId}/questions?size=10`)
    items.value = page.items
    total.value = page.total
  } catch {
    // fail-open：問答載不到不該讓商品頁看起來壞掉
    items.value = []
  } finally {
    loading.value = false
  }
}

async function submit() {
  if (content.value.trim().length < 5) {
    error.value = '問題至少需要 5 個字'
    return
  }
  submitting.value = true
  error.value = ''
  message.value = ''
  try {
    await request<QuestionView>(`/api/v1/catalog/products/${props.productId}/questions`,
      { method: 'POST', authenticated: true, body: { content: content.value.trim() } })
    content.value = ''
    // 明講要等回覆才會公開，否則使用者會以為送出失敗
    message.value = '已送出，我們回覆後會公開在這裡。'
  } catch (cause) {
    error.value = errorMessage(cause, '送出失敗')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section aria-labelledby="qna-heading">
    <div class="mb-4 flex items-end justify-between gap-4">
      <div>
        <p class="eyebrow mb-1">Q&amp;A</p>
        <h2 id="qna-heading" class="text-xl font-bold tracking-tight sm:text-2xl">商品問答</h2>
      </div>
      <p v-if="total > 0" class="figure text-sm text-ink-faint">{{ total }} 則</p>
    </div>

    <ul v-if="items.length > 0" class="flex flex-col gap-4">
      <li
        v-for="question in items"
        :key="question.questionId"
        class="rounded-sm border border-line bg-surface p-4 shadow-rest"
      >
        <div class="flex items-start gap-2">
          <span class="mt-0.5 text-sm font-semibold text-accent">Q</span>
          <div class="min-w-0 flex-1">
            <p class="text-sm leading-relaxed">{{ question.content }}</p>
            <p class="mt-1 text-xs text-ink-faint">{{ question.askedBy }}</p>
          </div>
        </div>
        <div v-if="question.answer" class="mt-3 flex items-start gap-2 border-t border-line pt-3">
          <span class="mt-0.5 text-sm font-semibold text-ink-muted">A</span>
          <p class="min-w-0 flex-1 text-sm leading-relaxed text-ink-muted">
            {{ question.answer }}
          </p>
        </div>
      </li>
    </ul>

    <EmptyState v-else-if="!loading" title="還沒有人提問。" description="有疑問可以在下面問，我們會回覆。" />

    <div v-if="auth.isAuthenticated" class="mt-5">
      <label class="text-sm text-ink-muted" for="qna-input">想問什麼？</label>
      <textarea
        id="qna-input"
        v-model="content"
        rows="3"
        maxlength="500"
        class="field mt-1.5 resize-y"
        placeholder="例如：這個尺寸適合幾公分的人？"
      />
      <div class="mt-2 flex items-center gap-3">
        <AppButton size="sm" :disabled="submitting" @click="submit">
          {{ submitting ? '送出中⋯' : '送出問題' }}
        </AppButton>
        <p v-if="message" class="text-xs text-ok">{{ message }}</p>
        <p v-if="error" class="text-xs text-danger">{{ error }}</p>
      </div>
    </div>
    <p v-else class="mt-5 text-sm text-ink-faint">登入後可以提問。</p>
  </section>
</template>
