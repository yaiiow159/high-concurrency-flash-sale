<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import type { QuestionPage, QuestionView } from '~/types/api'

definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { request } = useApi()

const items = ref<QuestionView[]>([])
const total = ref(0)
const loading = ref(true)
const drafts = reactive<Record<number, string>>({})
const message = ref('')
const error = ref('')

async function load() {
  loading.value = true
  try {
    const page = await request<QuestionPage>('/api/v1/admin/questions?size=50',
      { authenticated: true })
    items.value = page.items
    total.value = page.total
  } catch (cause) {
    error.value = errorMessage(cause, '讀取失敗')
  } finally {
    loading.value = false
  }
}

async function answer(question: QuestionView) {
  const draft = (drafts[question.questionId] ?? '').trim()
  if (!draft) {
    error.value = '回答不可為空'
    return
  }
  message.value = ''
  error.value = ''
  try {
    await request(`/api/v1/admin/questions/${question.questionId}/answer`,
      { method: 'POST', authenticated: true, body: { answer: draft } })
    // 回答與公開是同一個動作，所以回完就會從待回覆清單消失
    message.value = '已回答並公開'
    delete drafts[question.questionId]
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '回答失敗')
  }
}

async function hide(question: QuestionView) {
  if (!confirm('確定下架這個問題？')) {
    return
  }
  try {
    await request(`/api/v1/admin/questions/${question.questionId}/hide`,
      { method: 'POST', authenticated: true })
    message.value = '已下架'
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '操作失敗')
  }
}

onMounted(load)
useHead({ title: '問答管理 — 後台' })
</script>

<template>
  <div class="flex flex-col gap-6">
    <AdminPageHeader
      title="問答管理"
      description="回答後會自動公開在商品頁。等最久的排在最前面。"
    />

    <p v-if="message" class="rounded-sm bg-ok-soft px-3 py-2 text-sm text-ok">{{ message }}</p>
    <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger">{{ error }}</p>
    <p v-if="total > 0" class="text-sm text-ink-faint">
      待回覆 <span class="figure">{{ total }}</span> 則
    </p>

    <ul v-if="items.length > 0" class="flex flex-col gap-3">
      <li
        v-for="question in items"
        :key="question.questionId"
        class="rounded-sm border border-line bg-surface p-4 shadow-rest"
      >
        <div class="flex items-baseline gap-2">
          <NuxtLink
            :to="`/products/${question.productId}`"
            class="text-xs text-accent hover:underline"
          >
            商品 #{{ question.productId }}
          </NuxtLink>
          <span class="text-xs text-ink-faint">{{ question.askedBy }}</span>
        </div>
        <p class="mt-1.5 text-sm leading-relaxed">{{ question.content }}</p>
        <textarea
          v-model="drafts[question.questionId]"
          rows="2"
          maxlength="1000"
          class="field mt-3 resize-y"
          placeholder="回答內容（送出後會公開在商品頁）"
        />
        <div class="mt-2 flex gap-2">
          <AppButton size="sm" @click="answer(question)">回答並公開</AppButton>
          <AppButton size="sm" variant="ghost" @click="hide(question)">下架</AppButton>
        </div>
      </li>
    </ul>

    <EmptyState v-else-if="!loading" title="沒有待回覆的問題。" />
  </div>
</template>
