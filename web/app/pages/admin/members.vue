<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import { useAuthStore } from '~/stores/auth'
import type { UserView } from '~/types/api'

/**
 * 會員管理。停權是最原始的風控：一個帳號在搶什麼、退什麼，客服看到不對就先擋下來。
 * 停權後 refresh token 全部撤銷，access token 最長還能活 15 分鐘——這是 JWT 的取捨。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { users, suspendUser, reactivateUser } = useAdmin()
const auth = useAuthStore()

const STATUS_TABS = [
  { value: '', label: '全部' },
  { value: 'ACTIVE', label: '正常' },
  { value: 'SUSPENDED', label: '已停權' },
] as const

const ROLES: Record<string, string> = { CUSTOMER: '會員', ADMIN: '管理員' }
const PAGE_SIZE = 20

const keyword = ref('')
const tab = ref<string>('')
const page = ref(0)
const rows = ref<UserView[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await users(keyword.value.trim(), tab.value, page.value, PAGE_SIZE)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入會員')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0
  load()
}

async function suspend(user: UserView) {
  if (!confirm(`停權 ${user.email}？\n\n他會立刻無法登入，已登入的裝置最多 15 分鐘後失效。`)) {
    return
  }
  await act(user.userId, () => suspendUser(user.userId), '停權失敗')
}

async function reactivate(user: UserView) {
  await act(user.userId, () => reactivateUser(user.userId), '恢復失敗')
}

async function act(userId: number, action: () => Promise<unknown>, failure: string) {
  busy.value = userId
  error.value = null
  try {
    await action()
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, failure)
  } finally {
    busy.value = null
  }
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function formatTime(iso: string): string {
  return new Date(iso).toLocaleDateString('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

watch(tab, () => { page.value = 0; load() })
watch(page, load)
onMounted(load)

useHead({ title: '會員管理' })
</script>

<template>
  <div>
    <AdminPageHeader title="會員管理" description="查詢會員、停權與恢復">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <AppCard class="mb-4 p-4">
      <form class="flex flex-wrap items-end gap-3" @submit.prevent="search">
        <label class="flex min-w-[16rem] flex-1 flex-col gap-1 text-xs text-ink-muted">
          信箱或名稱
          <input v-model="keyword" type="search" class="field" placeholder="信箱前綴，或顯示名稱">
        </label>
        <AppButton type="submit" size="sm">搜尋</AppButton>
        <AppButton variant="ghost" size="sm" @click="keyword = ''; search()">清除</AppButton>
      </form>
    </AppCard>

    <AdminTabs v-model="tab" :tabs="STATUS_TABS" />

    <p
      v-if="error"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="loading" class="mt-4 flex flex-col gap-2">
      <SkeletonBlock class="h-14" />
      <SkeletonBlock class="h-14" />
      <SkeletonBlock class="h-14" />
    </div>

    <EmptyState v-else-if="rows.length === 0" class="mt-6" title="沒有符合條件的會員。" />

    <template v-else>
      <p class="figure mt-4 text-xs text-ink-faint">
        共 {{ total.toLocaleString() }} 筆 · 第 {{ page + 1 }} / {{ totalPages }} 頁
      </p>

      <AppCard class="mt-2 overflow-hidden">
        <div class="scroll-x">
          <table class="w-full text-sm">
            <thead class="bg-sunken text-left text-xs text-ink-muted">
              <tr>
                <th class="px-4 py-2.5 font-medium">ID</th>
                <th class="px-4 py-2.5 font-medium">信箱</th>
                <th class="px-4 py-2.5 font-medium">名稱</th>
                <th class="px-4 py-2.5 font-medium">角色</th>
                <th class="px-4 py-2.5 font-medium">狀態</th>
                <th class="px-4 py-2.5 font-medium">註冊</th>
                <th class="px-4 py-2.5 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-line">
              <tr v-for="user in rows" :key="user.userId" class="hover:bg-sunken/60">
                <td class="figure px-4 py-2.5 text-ink-muted">{{ user.userId }}</td>
                <td class="px-4 py-2.5">{{ user.email }}</td>
                <td class="px-4 py-2.5">{{ user.displayName }}</td>
                <td class="px-4 py-2.5">
                  <span
                    class="rounded-sm px-1.5 py-0.5 text-[11px] font-semibold"
                    :class="user.role === 'ADMIN' ? 'bg-accent-soft text-accent' : 'bg-sunken text-ink-muted'"
                  >
                    {{ ROLES[user.role] ?? user.role }}
                  </span>
                </td>
                <td class="px-4 py-2.5">
                  <span
                    class="inline-flex items-center gap-1.5 text-xs"
                    :class="user.status === 'SUSPENDED' ? 'text-danger' : 'text-ok'"
                  >
                    <span class="h-1.5 w-1.5 rounded-full bg-current" aria-hidden="true" />
                    {{ user.status === 'SUSPENDED' ? '已停權' : '正常' }}
                  </span>
                </td>
                <td class="figure px-4 py-2.5 text-xs text-ink-muted">{{ formatTime(user.createdAt) }}</td>
                <td class="px-4 py-2.5">
                  <div class="flex items-center justify-end gap-1">
                    <NuxtLink
                      :to="{ path: '/admin/orders', query: { userId: user.userId } }"
                      class="rounded-sm px-2 py-1 text-xs text-ink-muted transition-colors hover:text-accent"
                    >
                      訂單
                    </NuxtLink>
                    <!-- 黑名單只擋秒殺資格，不擋登入：多數刷單的處理到這裡就夠，不必停權 -->
                    <NuxtLink
                      v-if="user.role !== 'ADMIN'"
                      :to="{ path: '/admin/risk', query: { userId: user.userId } }"
                      class="rounded-sm px-2 py-1 text-xs text-ink-muted transition-colors hover:text-accent"
                    >
                      黑名單
                    </NuxtLink>
                    <!-- 管理員與自己不給停權鈕：按下去只會得到一個 403 -->
                    <AppButton
                      v-if="user.status === 'SUSPENDED'"
                      variant="secondary" size="sm" :disabled="busy === user.userId"
                      @click="reactivate(user)"
                    >
                      恢復
                    </AppButton>
                    <AppButton
                      v-else-if="user.role !== 'ADMIN' && user.email !== auth.userEmail"
                      variant="danger" size="sm" :disabled="busy === user.userId"
                      @click="suspend(user)"
                    >
                      停權
                    </AppButton>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <div class="mt-6 flex items-center justify-center gap-3">
        <AppButton variant="secondary" size="sm" :disabled="page === 0 || loading" @click="page--">
          上一頁
        </AppButton>
        <span class="figure text-xs text-ink-muted">{{ page + 1 }} / {{ totalPages }}</span>
        <AppButton
          variant="secondary" size="sm" :disabled="page + 1 >= totalPages || loading" @click="page++"
        >
          下一頁
        </AppButton>
      </div>
    </template>
  </div>
</template>
