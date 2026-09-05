<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { ShipmentView } from '~/types/api'

/**
 * 出貨處理。
 *
 * 這一頁是**工作佇列**而不是資料表：維運人員來這裡是為了把一批單子推進到下一個狀態，
 * 因此每一列的主要動作直接放在列上，不必先點進詳情。
 *
 * **一次處理一筆，沒有批次操作。** 每一筆的失敗處理都不同
 * （承運商拒收、單號打錯、訂單已取消），批次介面會把
 * 「哪幾筆失敗了」變成一個新的 UI 問題（ADR-0015「不做的事」）。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { shipments, dispatch, markDelivered, markFailed } = useAdmin()

/**
 * 承運商。與後端的 `Carrier` 列舉一一對應——
 * 打錯值的話後端回 400，而使用者看到的是一個沒有上下文的錯誤訊息。
 * 做成下拉選單就不會有打錯這回事。
 */
const CARRIERS = [
  { value: 'TCAT', label: '黑貓宅急便' },
  { value: 'HCT', label: '新竹物流' },
  { value: 'POST', label: '中華郵政' },
  { value: 'CVS', label: '超商取貨' },
  { value: 'SELF', label: '自行配送' },
] as const

/**
 * 狀態值必須與後端的 `ShipmentStatus` 一字不差。
 *
 * 打錯的話後端連參數都轉不出來，回的是一個沒有上下文的錯誤——
 * 而畫面上只會看到「載入失敗」。這裡曾經寫成 PENDING/SHIPPED，
 * 而真正的值是 READY/IN_TRANSIT。
 *
 * 不放 CANCELLED：那是出貨前取消，屬於訂單那一側的事，
 * 出貨佇列裡沒有任何動作可以對它做。
 */
const TABS = [
  { value: 'READY', label: '待出貨' },
  { value: 'IN_TRANSIT', label: '運送中' },
  { value: 'DELIVERED', label: '已送達' },
  { value: 'FAILED', label: '配送失敗' },
] as const

const tab = ref<string>('READY')
const rows = ref<ShipmentView[]>([])
const loading = ref(true)
const error = ref<string | null>(null)

/** 正在填出貨資訊的那一筆；null 代表沒有。 */
const dispatching = ref<string | null>(null)
const carrier = ref<string>('TCAT')
const trackingNumber = ref('')
const submitting = ref(false)

async function load() {
  loading.value = true
  error.value = null
  try {
    rows.value = await shipments(tab.value)
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入出貨清單')
    rows.value = []
  } finally {
    loading.value = false
  }
}

function startDispatch(orderNo: string) {
  dispatching.value = orderNo
  carrier.value = 'TCAT'
  trackingNumber.value = ''
  error.value = null
}

async function confirmDispatch() {
  if (!dispatching.value || !trackingNumber.value.trim()) {
    return
  }
  submitting.value = true
  error.value = null
  try {
    await dispatch(dispatching.value, carrier.value, trackingNumber.value.trim())
    dispatching.value = null
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '出貨失敗')
  } finally {
    submitting.value = false
  }
}

/**
 * 標記送達。
 *
 * **要二次確認**：這一步會啟動退貨期限的計時，而且不可逆
 * （沒有「取消送達」這個動作）。誤按的成本由買家承擔。
 */
async function confirmDelivered(orderNo: string) {
  if (!confirm(`確定訂單 ${orderNo} 已送達？\n\n這會啟動退貨期限的計時，而且無法取消。`)) {
    return
  }
  try {
    await markDelivered(orderNo)
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '標記送達失敗')
  }
}

/** 配送失敗不是終態——可以再次出貨，因此不需要二次確認。 */
async function reportFailure(orderNo: string) {
  const reason = prompt('配送失敗的原因？（會記錄在出貨單上）')
  if (!reason?.trim()) {
    return
  }
  try {
    await markFailed(orderNo, reason.trim())
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '標記失敗')
  }
}

function formatTime(iso: string | null): string {
  return iso
    ? new Date(iso).toLocaleString('zh-TW', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    })
    : '—'
}

watch(tab, load)
onMounted(load)

useHead({ title: '出貨處理' })
</script>

<template>
  <div>
    <AdminPageHeader title="出貨處理" description="把已付款的訂單交付承運商並追蹤配送">
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
      <SkeletonBlock class="h-16" />
      <SkeletonBlock class="h-16" />
    </div>

    <EmptyState
      v-else-if="rows.length === 0"
      class="mt-6"
      :title="`目前沒有${TABS.find((t) => t.value === tab)?.label}的單子。`"
      hint="這是好消息——代表這個狀態沒有積壓。"
    />

    <ul v-else class="mt-4 flex flex-col gap-2">
      <li v-for="row in rows" :key="row.shipmentNo">
        <AppCard class="p-4">
          <div class="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
            <div class="min-w-0">
              <div class="flex flex-wrap items-center gap-2">
                <NuxtLink
                  :to="`/orders/${row.orderNo}`"
                  class="figure text-sm font-medium transition-colors hover:text-accent"
                >
                  {{ row.orderNo }}
                </NuxtLink>
                <StatusBadge :status="row.status" />
                <!-- 重送次數只在大於 1 時顯示——「第 1 次配送」是雜訊 -->
                <span
                  v-if="row.dispatchCount > 1"
                  class="rounded-sm bg-surface-sunken px-1.5 py-0.5 text-[11px] text-ink-faint"
                >
                  第 {{ row.dispatchCount }} 次配送
                </span>
              </div>

              <dl class="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs text-ink-muted">
                <div v-if="row.carrierName" class="flex gap-1.5">
                  <dt class="text-ink-faint">承運商</dt>
                  <dd>{{ row.carrierName }}</dd>
                </div>
                <div v-if="row.trackingNumber" class="flex gap-1.5">
                  <dt class="text-ink-faint">單號</dt>
                  <dd class="figure">{{ row.trackingNumber }}</dd>
                </div>
                <div v-if="row.shippedAt" class="flex gap-1.5">
                  <dt class="text-ink-faint">出貨</dt>
                  <dd class="figure">{{ formatTime(row.shippedAt) }}</dd>
                </div>
                <div v-if="row.deliveredAt" class="flex gap-1.5">
                  <dt class="text-ink-faint">送達</dt>
                  <dd class="figure">{{ formatTime(row.deliveredAt) }}</dd>
                </div>
              </dl>

              <p v-if="row.failureReason" class="mt-2 text-xs text-danger">
                配送失敗：{{ row.failureReason }}
              </p>
            </div>

            <div class="flex shrink-0 flex-wrap items-center gap-2">
              <AppButton
                v-if="row.status === 'READY' || row.status === 'FAILED'"
                size="sm" @click="startDispatch(row.orderNo)"
              >
                {{ row.status === 'FAILED' ? '重新出貨' : '出貨' }}
              </AppButton>
              <template v-if="row.status === 'IN_TRANSIT'">
                <AppButton size="sm" @click="confirmDelivered(row.orderNo)">
                  標記送達
                </AppButton>
                <AppButton variant="secondary" size="sm" @click="reportFailure(row.orderNo)">
                  配送失敗
                </AppButton>
              </template>
            </div>
          </div>

          <!-- 出貨表單就地展開，不換頁：維運要連續處理一整批 -->
          <div
            v-if="dispatching === row.orderNo"
            class="mt-4 flex flex-wrap items-end gap-3 border-t border-line pt-4"
          >
            <label class="flex flex-col gap-1.5 text-xs">
              <span class="eyebrow">承運商</span>
              <select
                v-model="carrier"
                class="h-10 rounded-sm border border-line bg-surface px-3 text-sm"
              >
                <option v-for="option in CARRIERS" :key="option.value" :value="option.value">
                  {{ option.label }}
                </option>
              </select>
            </label>
            <label class="flex min-w-0 flex-1 flex-col gap-1.5 text-xs">
              <span class="eyebrow">物流單號</span>
              <input
                v-model="trackingNumber"
                type="text"
                placeholder="例如 TC123456789"
                class="figure h-10 w-full rounded-sm border border-line bg-surface px-3 text-sm"
                @keyup.enter="confirmDispatch"
              >
            </label>
            <div class="flex gap-2">
              <AppButton variant="secondary" size="sm" @click="dispatching = null">
                取消
              </AppButton>
              <AppButton
                size="sm" :disabled="submitting || !trackingNumber.trim()"
                @click="confirmDispatch"
              >
                {{ submitting ? '處理中⋯' : '確認出貨' }}
              </AppButton>
            </div>
          </div>
        </AppCard>
      </li>
    </ul>
  </div>
</template>
