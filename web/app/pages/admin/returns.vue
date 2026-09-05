<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ReturnRequestView } from '~/types/api'

/**
 * 退貨審核。
 *
 * 每一列要讓客服在**不點進去**的情況下就做得了判斷，因此列上直接帶
 * 退貨原因、品項與退款金額——少了任一項都會逼他先點開再回來。
 *
 * **駁回必須填理由，核准不必。** 這不是對稱的：核准之後使用者拿得到錢，
 * 沒有人會問為什麼；駁回則是一個壞消息，而收到壞消息卻不知道原因
 * 只會變成一通客服電話。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { returns, approveReturn, rejectReturn } = useAdmin()

const TABS = [
  { value: 'REQUESTED', label: '待審核' },
  { value: 'APPROVED', label: '已核准' },
  { value: 'RECEIVED', label: '已收貨' },
  { value: 'REFUNDED', label: '已退款' },
  { value: 'REJECTED', label: '已駁回' },
] as const

/** 退貨原因的顯示文字。責任歸屬由原因決定，客服看的就是這一欄。 */
const REASONS: Record<string, string> = {
  DEFECTIVE: '商品瑕疵',
  NOT_AS_DESCRIBED: '與描述不符',
  WRONG_ITEM: '出錯商品',
  CHANGED_MIND: '不想要了',
  OTHER: '其他',
}

/**
 * 需要商家負責的原因。
 *
 * 只用於**提示客服**，不影響任何金額計算——真正的責任歸屬在後端由原因決定。
 * 前端再算一次的話，兩邊的判斷遲早會分歧，而分歧之後沒有人知道哪一邊是對的。
 */
const MERCHANT_FAULT = new Set(['DEFECTIVE', 'NOT_AS_DESCRIBED', 'WRONG_ITEM'])

const tab = ref<string>('REQUESTED')
const rows = ref<ReturnRequestView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<string | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await returns(tab.value)
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入退貨清單')
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function approve(row: ReturnRequestView) {
  if (!confirm(`核准 ${row.returnNo}？\n\n退款金額 NT$ ${row.refundAmount.toLocaleString()}。`)) {
    return
  }
  busy.value = row.returnNo
  error.value = null
  try {
    await approveReturn(row.returnNo)
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '核准失敗')
  } finally {
    busy.value = null
  }
}

async function reject(row: ReturnRequestView) {
  const note = prompt('駁回的理由？使用者會看到這句話。')
  if (!note?.trim()) {
    return
  }
  busy.value = row.returnNo
  error.value = null
  try {
    await rejectReturn(row.returnNo, note.trim())
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '駁回失敗')
  } finally {
    busy.value = null
  }
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-TW', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

watch(tab, load)
onMounted(load)

useHead({ title: '退貨審核' })
</script>

<template>
  <div>
    <AdminPageHeader title="退貨審核" description="判斷退貨申請是否成立，並追蹤收貨與退款">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <AdminTabs v-model="tab" :tabs="TABS" />

    <p
      v-if="error"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="loading" class="mt-4 flex flex-col gap-2">
      <SkeletonBlock class="h-24" />
      <SkeletonBlock class="h-24" />
    </div>

    <EmptyState
      v-else-if="rows.length === 0"
      class="mt-6"
      :title="`目前沒有${TABS.find((t) => t.value === tab)?.label}的退貨單。`"
    />

    <ul v-else class="mt-4 flex flex-col gap-2">
      <li v-for="row in rows" :key="row.returnNo">
        <AppCard class="p-4">
          <div class="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="figure text-sm font-medium">{{ row.returnNo }}</span>
                <StatusBadge :status="row.status" />
                <span
                  class="rounded-sm px-1.5 py-0.5 text-[11px]"
                  :class="MERCHANT_FAULT.has(row.reason)
                    ? 'bg-danger-soft text-danger'
                    : 'bg-surface-sunken text-ink-faint'"
                >
                  {{ REASONS[row.reason] ?? row.reason }}
                </span>
                <span class="figure text-xs text-ink-faint">{{ formatTime(row.createdAt) }}</span>
              </div>

              <p v-if="row.reasonDetail" class="mt-2 text-sm text-ink-muted">
                「{{ row.reasonDetail }}」
              </p>

              <ul class="mt-2 flex flex-col gap-0.5 text-xs text-ink-muted">
                <li v-for="line in row.lines" :key="line.skuId" class="flex gap-2">
                  <span class="truncate">{{ line.skuSnapshot }}</span>
                  <span class="figure shrink-0 text-ink-faint">× {{ line.quantity }}</span>
                </li>
              </ul>

              <p class="mt-2 text-xs">
                <NuxtLink
                  :to="`/orders/${row.orderNo}`"
                  class="figure text-ink-faint transition-colors hover:text-accent"
                >
                  訂單 {{ row.orderNo }} →
                </NuxtLink>
              </p>

              <p v-if="row.reviewNote" class="mt-2 text-xs text-ink-faint">
                審核備註：{{ row.reviewNote }}
              </p>
            </div>

            <div class="flex shrink-0 flex-col items-end gap-2">
              <div class="text-right">
                <p class="eyebrow">退款金額</p>
                <MoneyText :amount="row.refundAmount" size="lg" />
                <!-- 需不需要寄回實體商品，決定核准之後的下一步是什麼 -->
                <p class="mt-0.5 text-[11px] text-ink-faint">
                  {{ row.requiresGoodsReturn ? '需寄回商品' : '無需寄回' }}
                </p>
              </div>

              <div v-if="row.status === 'REQUESTED'" class="flex gap-2">
                <AppButton
                  variant="secondary" size="sm"
                  :disabled="busy === row.returnNo" @click="reject(row)"
                >
                  駁回
                </AppButton>
                <AppButton size="sm" :disabled="busy === row.returnNo" @click="approve(row)">
                  {{ busy === row.returnNo ? '處理中⋯' : '核准' }}
                </AppButton>
              </div>
            </div>
          </div>
        </AppCard>
      </li>
    </ul>
  </div>
</template>
