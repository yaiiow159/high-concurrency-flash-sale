<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { BlacklistView } from '~/types/api'

/**
 * 秒殺黑名單。與停權不同：被列入的人仍可登入、逛、下一般訂單，只是拿不到搶購資格。
 * 客服認定「這個人在刷秒殺」時，多數情況不需要把整個帳號關掉。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { blacklist, addToBlacklist, removeFromBlacklist } = useAdmin()
const route = useRoute()

const PAGE_SIZE = 20
const page = ref(0)
const rows = ref<BlacklistView[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<number | null>(null)

const form = ref({
  userId: typeof route.query.userId === 'string' ? route.query.userId : '',
  reason: '',
  expiresAt: '',
})
const saving = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await blacklist(page.value, PAGE_SIZE)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入黑名單')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function submit() {
  saving.value = true
  error.value = null
  try {
    await addToBlacklist(Number(form.value.userId), form.value.reason.trim(),
      form.value.expiresAt ? new Date(form.value.expiresAt).toISOString() : null)
    form.value = { userId: '', reason: '', expiresAt: '' }
    page.value = 0
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '列入黑名單失敗')
  } finally {
    saving.value = false
  }
}

async function remove(row: BlacklistView) {
  if (!confirm(`將使用者 ${row.userId} 移出黑名單？`)) {
    return
  }
  busy.value = row.userId
  error.value = null
  try {
    await removeFromBlacklist(row.userId)
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '移除失敗')
  } finally {
    busy.value = null
  }
}

function expired(row: BlacklistView): boolean {
  return row.expiresAt !== null && new Date(row.expiresAt).getTime() <= Date.now()
}

function formatTime(iso: string | null): string {
  if (!iso) return '永久'
  return new Date(iso).toLocaleString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

watch(page, load)
onMounted(load)

useHead({ title: '風控黑名單' })
</script>

<template>
  <div>
    <AdminPageHeader title="風控黑名單" description="列入的人仍可登入與下一般訂單，只是拿不到搶購資格；要整個帳號停掉請用會員管理的停權">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <AppCard class="mb-4 p-4">
      <form class="flex flex-wrap items-end gap-3" @submit.prevent="submit">
        <label class="flex w-32 flex-col gap-1 text-xs text-ink-muted">
          使用者 ID
          <input v-model="form.userId" type="number" min="1" required class="field figure" placeholder="例如 42">
        </label>
        <label class="flex min-w-[16rem] flex-1 flex-col gap-1 text-xs text-ink-muted">
          原因（內部用，不會給使用者看）
          <input v-model="form.reason" required maxlength="200" class="field" placeholder="例如：同一裝置 12 個帳號搶同一檔">
        </label>
        <label class="flex w-52 flex-col gap-1 text-xs text-ink-muted">
          到期（空白 = 永久）
          <input v-model="form.expiresAt" type="datetime-local" class="field figure">
        </label>
        <AppButton type="submit" size="sm" :disabled="saving">{{ saving ? '處理中⋯' : '列入黑名單' }}</AppButton>
      </form>
    </AppCard>

    <p
      v-if="error"
      class="rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="loading" class="mt-4 flex flex-col gap-2">
      <SkeletonBlock class="h-14" />
      <SkeletonBlock class="h-14" />
    </div>

    <EmptyState v-else-if="rows.length === 0" class="mt-6" title="黑名單是空的。" hint="風險評分自動擋下的人不會出現在這裡；這裡只有客服手動列入的。" />

    <template v-else>
      <p class="figure mt-4 text-xs text-ink-faint">
        共 {{ total.toLocaleString() }} 筆 · 第 {{ page + 1 }} / {{ totalPages }} 頁
      </p>

      <AppCard class="mt-2 overflow-hidden">
        <div class="scroll-x">
          <table class="w-full text-sm">
            <thead class="bg-sunken text-left text-xs text-ink-muted">
              <tr>
                <th class="px-4 py-2.5 font-medium">使用者</th>
                <th class="px-4 py-2.5 font-medium">原因</th>
                <th class="px-4 py-2.5 font-medium">列入</th>
                <th class="px-4 py-2.5 font-medium">到期</th>
                <th class="px-4 py-2.5 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-line">
              <tr v-for="row in rows" :key="row.userId" :class="expired(row) ? 'opacity-50' : ''">
                <td class="px-4 py-2.5">
                  <span class="figure text-ink-muted">#{{ row.userId }}</span>
                  <span v-if="row.email" class="ml-2">{{ row.email }}</span>
                  <span v-if="row.displayName" class="ml-1 text-ink-muted">（{{ row.displayName }}）</span>
                </td>
                <td class="px-4 py-2.5">{{ row.reason }}</td>
                <td class="figure px-4 py-2.5 text-xs text-ink-muted">{{ formatTime(row.createdAt) }}</td>
                <td class="figure px-4 py-2.5 text-xs" :class="expired(row) ? 'text-ink-faint' : 'text-ink-muted'">
                  {{ expired(row) ? '已過期' : formatTime(row.expiresAt) }}
                </td>
                <td class="px-4 py-2.5">
                  <div class="flex items-center justify-end gap-1">
                    <NuxtLink
                      :to="{ path: '/admin/orders', query: { userId: row.userId } }"
                      class="rounded-sm px-2 py-1 text-xs text-ink-muted transition-colors hover:text-accent"
                    >
                      訂單
                    </NuxtLink>
                    <AppButton variant="secondary" size="sm" :disabled="busy === row.userId" @click="remove(row)">
                      移除
                    </AppButton>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </AppCard>

      <div class="mt-6 flex items-center justify-center gap-3">
        <AppButton variant="secondary" size="sm" :disabled="page === 0 || loading" @click="page--">上一頁</AppButton>
        <span class="figure text-xs text-ink-muted">{{ page + 1 }} / {{ totalPages }}</span>
        <AppButton variant="secondary" size="sm" :disabled="page + 1 >= totalPages || loading" @click="page++">下一頁</AppButton>
      </div>
    </template>
  </div>
</template>
