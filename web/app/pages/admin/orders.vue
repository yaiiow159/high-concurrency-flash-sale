<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { OrderView } from '~/types/api'

/**
 * 訂單管理。客服接到電話時的第一個動作是「幫我查這張單」，
 * 所以搜尋框放在最上面、訂單號優先，列上直接帶金額與狀態。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { orders, closeOrder, staffNote, writeStaffNote } = useAdmin()
const route = useRoute()

const STATUS_TABS = [
  { value: '', label: '全部' },
  { value: 'PENDING_PAYMENT', label: '待付款' },
  { value: 'PAID', label: '已付款' },
  { value: 'SHIPPED', label: '已出貨' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'REFUNDED', label: '已退款' },
] as const

const PAGE_SIZE = 20

const orderNoInput = ref('')
const userIdInput = ref(typeof route.query.userId === 'string' ? route.query.userId : '')
const tab = ref<string>('')
const page = ref(0)
const rows = ref<OrderView[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<string | null>(null)

/** 展開的那一列。一次只展開一張：客服看的是「這一張」，不是比較 */
const expanded = ref<string | null>(null)
const noteDraft = ref('')
const noteSaved = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await orders({
      orderNo: orderNoInput.value.trim() || undefined,
      userId: userIdInput.value ? Number(userIdInput.value) : null,
      status: tab.value || undefined,
    }, page.value, PAGE_SIZE)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入訂單')
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

function reset() {
  orderNoInput.value = ''
  userIdInput.value = ''
  tab.value = ''
  page.value = 0
  load()
}

async function toggle(order: OrderView) {
  if (expanded.value === order.orderNo) {
    expanded.value = null
    return
  }
  expanded.value = order.orderNo
  noteSaved.value = false
  noteDraft.value = ''
  try {
    noteDraft.value = (await staffNote(order.orderNo)).note ?? ''
  } catch {
    noteDraft.value = ''
  }
}

async function saveNote(orderNo: string) {
  busy.value = orderNo
  error.value = null
  try {
    await writeStaffNote(orderNo, noteDraft.value.trim())
    noteSaved.value = true
  } catch (cause) {
    error.value = errorMessage(cause, '儲存註記失敗')
  } finally {
    busy.value = null
  }
}

async function close(order: OrderView) {
  const reason = prompt(`關閉訂單 ${order.orderNo}？\n\n這會退回庫存。請填寫原因（買家看得到）：`)
  if (!reason?.trim()) {
    return
  }
  busy.value = order.orderNo
  error.value = null
  try {
    await closeOrder(order.orderNo, reason.trim())
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '關單失敗')
  } finally {
    busy.value = null
  }
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-TW', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

const CHANNELS: Record<string, string> = { SECKILL: '秒殺', NORMAL: '一般' }

watch(tab, () => { page.value = 0; load() })
watch(page, load)
onMounted(load)

useHead({ title: '訂單管理' })
</script>

<template>
  <div>
    <AdminPageHeader title="訂單管理" description="查單、看明細、手動關閉待付款訂單">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <!-- 搜尋列：訂單號是精確比對，使用者 ID 通常是從會員頁帶過來的 -->
    <AppCard class="mb-4 p-4">
      <form class="flex flex-wrap items-end gap-3" @submit.prevent="search">
        <label class="flex min-w-[16rem] flex-1 flex-col gap-1 text-xs text-ink-muted">
          訂單編號
          <input v-model="orderNoInput" type="search" class="field figure" placeholder="完整訂單號">
        </label>
        <label class="flex w-40 flex-col gap-1 text-xs text-ink-muted">
          使用者 ID
          <input v-model="userIdInput" type="number" min="1" class="field figure" placeholder="例如 42">
        </label>
        <AppButton type="submit" size="sm">搜尋</AppButton>
        <AppButton variant="ghost" size="sm" @click="reset">清除</AppButton>
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
      <SkeletonBlock class="h-16" />
      <SkeletonBlock class="h-16" />
      <SkeletonBlock class="h-16" />
    </div>

    <EmptyState v-else-if="rows.length === 0" class="mt-6" title="沒有符合條件的訂單。" />

    <template v-else>
      <p class="figure mt-4 text-xs text-ink-faint">
        共 {{ total.toLocaleString() }} 筆 · 第 {{ page + 1 }} / {{ totalPages }} 頁
      </p>

      <ul class="mt-2 flex flex-col gap-2">
        <li v-for="order in rows" :key="order.orderNo">
          <AppCard :highlighted="expanded === order.orderNo">
            <button
              type="button"
              class="flex w-full flex-wrap items-center gap-x-5 gap-y-2 p-4 text-left"
              :aria-expanded="expanded === order.orderNo"
              @click="toggle(order)"
            >
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <span class="figure text-sm">{{ order.orderNo }}</span>
                  <StatusBadge :status="order.status" />
                  <span class="rounded-sm bg-sunken px-1.5 py-0.5 text-[11px] text-ink-muted">
                    {{ CHANNELS[order.channel ?? ''] ?? order.channel }}
                  </span>
                </div>
                <p class="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-ink-muted">
                  <span>使用者 <span class="figure">{{ order.userId ?? '—' }}</span></span>
                  <span>{{ order.lines.length }} 項</span>
                  <span>{{ formatTime(order.createdAt) }}</span>
                  <span v-if="order.closeReason" class="text-danger">{{ order.closeReason }}</span>
                </p>
              </div>
              <MoneyText :amount="order.payableAmount" size="md" class="shrink-0" />
              <svg
                viewBox="0 0 24 24" class="h-4 w-4 shrink-0 text-ink-faint transition-transform"
                :class="expanded === order.orderNo ? 'rotate-180' : ''"
                fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"
              >
                <path d="m6 9 6 6 6-6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>

            <div v-if="expanded === order.orderNo" class="border-t border-line px-4 py-4">
              <div class="grid gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
                <div>
                  <p class="eyebrow mb-2">品項</p>
                  <table class="w-full text-sm">
                    <tbody class="divide-y divide-line">
                      <tr v-for="line in order.lines" :key="line.skuId">
                        <td class="py-2 pr-3">{{ line.skuSnapshot }}</td>
                        <td class="figure py-2 pr-3 text-right text-ink-muted">× {{ line.quantity }}</td>
                        <td class="figure py-2 text-right">
                          NT$ {{ line.paidAmount.toLocaleString() }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                  <dl class="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs text-ink-muted">
                    <dt>商品小計</dt>
                    <dd class="figure text-right text-ink">NT$ {{ order.subtotal?.toLocaleString() ?? '—' }}</dd>
                    <template v-for="discount in order.discounts" :key="discount.name">
                      <dt>{{ discount.name }}</dt>
                      <dd class="figure text-right text-ok">− NT$ {{ discount.amount.toLocaleString() }}</dd>
                    </template>
                    <dt>運費</dt>
                    <dd class="figure text-right text-ink">NT$ {{ order.shippingFee?.toLocaleString() ?? '—' }}</dd>
                    <dt class="font-semibold text-ink">應付</dt>
                    <dd class="figure text-right font-semibold text-ink">
                      NT$ {{ order.payableAmount?.toLocaleString() ?? '—' }}
                    </dd>
                  </dl>
                </div>

                <div class="flex flex-col gap-4">
                  <div v-if="order.shipping">
                    <p class="eyebrow mb-1">收件人</p>
                    <p class="text-sm">{{ order.shipping.recipientName }} · {{ order.shipping.phone }}</p>
                    <p class="text-xs text-ink-muted">{{ order.shipping.fullAddress }}</p>
                  </div>
                  <div v-if="order.buyerNote">
                    <p class="eyebrow mb-1">買家備註</p>
                    <p class="text-sm text-ink-muted">{{ order.buyerNote }}</p>
                  </div>
                  <div>
                    <p class="eyebrow mb-1">內部註記</p>
                    <!-- 只有後台看得到；買家備註與內部註記絕不共用一欄 -->
                    <textarea
                      v-model="noteDraft" rows="3" maxlength="500"
                      class="field text-sm" placeholder="只有後台看得到"
                    />
                    <div class="mt-2 flex items-center gap-2">
                      <AppButton
                        variant="secondary" size="sm" :disabled="busy === order.orderNo"
                        @click="saveNote(order.orderNo)"
                      >
                        儲存註記
                      </AppButton>
                      <span v-if="noteSaved" class="text-xs text-ok">已儲存</span>
                    </div>
                  </div>
                  <div class="flex flex-wrap gap-2 border-t border-line pt-4">
                    <NuxtLink
                      :to="{ path: '/admin/orders', query: { userId: order.userId } }"
                      class="rounded-sm px-2 py-1.5 text-xs text-ink-muted transition-colors hover:text-accent"
                      @click="userIdInput = String(order.userId ?? ''); search()"
                    >
                      此使用者的其他訂單 →
                    </NuxtLink>
                    <AppButton
                      v-if="order.status === 'PENDING_PAYMENT'"
                      variant="danger" size="sm" class="ml-auto"
                      :disabled="busy === order.orderNo"
                      @click="close(order)"
                    >
                      關閉訂單
                    </AppButton>
                  </div>
                </div>
              </div>
            </div>
          </AppCard>
        </li>
      </ul>

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
