<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

const auth = useAuthStore()

const mode = ref<'login' | 'register'>('login')
const email = ref('')
const password = ref('')
const displayName = ref('')
const busy = ref(false)
const error = ref<string | null>(null)

async function submit(): Promise<void> {
  busy.value = true
  error.value = null
  try {
    if (mode.value === 'login') {
      await auth.login(email.value, password.value)
    } else {
      await auth.register(email.value, password.value, displayName.value)
    }
    password.value = ''
  } catch (e) {
    // 後端刻意讓「帳號不存在」與「密碼錯誤」回相同訊息，
    // 這裡照樣呈現即可——不要自作聰明去區分。
    const body = (e as { data?: { message?: string } }).data
    error.value = body?.message ?? '操作失敗，請稍後再試'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="rounded border border-line bg-surface p-5">
    <template v-if="auth.isAuthenticated">
      <div class="flex items-center justify-between gap-4">
        <div class="text-sm">
          <div class="font-medium">已登入</div>
          <div class="text-ink-muted">{{ auth.userEmail ?? '' }}</div>
        </div>
        <button
          type="button"
          class="rounded-sm border border-line px-3 py-1.5 text-sm transition-colors hover:border-accent hover:text-accent"
          @click="auth.logout()"
        >
          登出
        </button>
      </div>
    </template>

    <template v-else>
      <div class="mb-3 flex gap-4 text-sm">
        <button
          type="button"
          :class="mode === 'login' ? 'font-semibold text-accent' : 'text-ink-muted hover:text-ink'"
          @click="mode = 'login'"
        >
          登入
        </button>
        <button
          type="button"
          :class="mode === 'register' ? 'font-semibold text-accent' : 'text-ink-muted hover:text-ink'"
          @click="mode = 'register'"
        >
          註冊
        </button>
      </div>

      <form class="flex flex-col gap-2" @submit.prevent="submit">
        <input
          v-model="email"
          type="email"
          required
          placeholder="電子郵件"
          autocomplete="email"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
        <input
          v-model="password"
          type="password"
          required
          minlength="8"
          placeholder="密碼（至少 8 碼）"
          :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
        <input
          v-if="mode === 'register'"
          v-model="displayName"
          required
          placeholder="顯示名稱"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
        <AppButton type="submit" :disabled="busy" block>
          {{ busy ? '處理中⋯' : mode === 'login' ? '登入' : '註冊並登入' }}
        </AppButton>
      </form>

      <p v-if="error" class="mt-2 text-sm text-danger">{{ error }}</p>
    </template>
  </div>
</template>
