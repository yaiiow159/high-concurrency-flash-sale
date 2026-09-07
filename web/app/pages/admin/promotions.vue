<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin } from '~/composables/useAdmin'
import type { PromotionAdminView, PromotionRequest } from '~/types/api'

/**
 * 優惠管理。規則與券分開：這裡管規則，券是規則發給某個人的實例，只看發了幾張、用了幾張。
 * 停用是即時的——已發出的券即刻不可用，所以按鈕要確認。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { promotions, createPromotion, updatePromotion, setPromotionEnabled } = useAdmin()

const TYPE_TABS = [
  { value: '', label: '全部' },
  { value: 'COUPON', label: '優惠券' },
  { value: 'ORDER_DISCOUNT', label: '整單折扣' },
  { value: 'ITEM_DISCOUNT', label: '單品折扣' },
  { value: 'SHIPPING', label: '運費' },
] as const

const TYPES: Record<string, string> = {
  COUPON: '優惠券', ORDER_DISCOUNT: '整單折扣', ITEM_DISCOUNT: '單品折扣', SHIPPING: '運費',
}
const PAGE_SIZE = 20

const tab = ref<string>('')
const page = ref(0)
const rows = ref<PromotionAdminView[]>([])
const total = ref(0)
const loading = ref(true)
const error = ref<string | null>(null)
const busy = ref<number | null>(null)

/** 表單：null 代表收起；editing 為 null 代表新增 */
const form = ref<PromotionRequest | null>(null)
const editing = ref<number | null>(null)
const saving = ref(false)

function blankForm(): PromotionRequest {
  const start = new Date()
  const end = new Date(start.getTime() + 30 * 86400_000)
  return {
    name: '', type: 'COUPON', rule: 'FIXED_AMOUNT', threshold: 0, value: 100,
    maxDiscount: null, pointCost: null,
    startAt: toLocalInput(start), endAt: toLocalInput(end), enabled: true,
  }
}

/** datetime-local 需要「無時區」的字串；送出時再轉回 ISO */
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
    + `T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function openCreate() {
  editing.value = null
  form.value = blankForm()
}

function openEdit(promotion: PromotionAdminView) {
  editing.value = promotion.id
  form.value = {
    name: promotion.name, type: promotion.type, rule: promotion.rule,
    threshold: promotion.threshold, value: promotion.value,
    maxDiscount: promotion.maxDiscount, pointCost: promotion.pointCost,
    startAt: toLocalInput(new Date(promotion.startAt)),
    endAt: toLocalInput(new Date(promotion.endAt)),
    enabled: promotion.enabled,
  }
}

async function submit() {
  if (!form.value) return
  saving.value = true
  error.value = null
  const body: PromotionRequest = {
    ...form.value,
    threshold: Number(form.value.threshold) || 0,
    value: Number(form.value.value),
    maxDiscount: form.value.maxDiscount === null || form.value.maxDiscount === ('' as unknown)
      ? null : Number(form.value.maxDiscount),
    pointCost: form.value.pointCost === null || form.value.pointCost === ('' as unknown)
      ? null : Number(form.value.pointCost),
    startAt: new Date(form.value.startAt).toISOString(),
    endAt: new Date(form.value.endAt).toISOString(),
  }
  try {
    if (editing.value === null) {
      await createPromotion(body)
    } else {
      await updatePromotion(editing.value, body)
    }
    form.value = null
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '儲存失敗')
  } finally {
    saving.value = false
  }
}

async function load() {
  loading.value = true
  error.value = null
  try {
    const result = await promotions(tab.value, page.value, PAGE_SIZE)
    rows.value = result.items
    total.value = result.total
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入優惠')
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function toggle(promotion: PromotionAdminView) {
  if (promotion.enabled && !confirm(`停用「${promotion.name}」？\n\n已發出的 ${promotion.issuedCoupons} 張券會即刻不可用。`)) {
    return
  }
  busy.value = promotion.id
  error.value = null
  try {
    await setPromotionEnabled(promotion.id, !promotion.enabled)
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '更新失敗')
  } finally {
    busy.value = null
  }
}

/** 一句話說清楚這個規則折多少 */
function describe(p: PromotionAdminView): string {
  const threshold = p.threshold > 0 ? `滿 ${p.threshold.toLocaleString()} ` : ''
  // 運費優惠的 value 是「最多折掉多少運費」的內部表示，對營運來說它就是免運
  if (p.type === 'SHIPPING') {
    return `${threshold}免運`
  }
  if (p.rule === 'PERCENTAGE') {
    const cap = p.maxDiscount ? `（上限 ${p.maxDiscount.toLocaleString()}）` : ''
    return `${threshold}折 ${Math.round(p.value * 100)}%${cap}`
  }
  return `${threshold}折 NT$ ${p.value.toLocaleString()}`
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('zh-TW', { year: 'numeric', month: '2-digit', day: '2-digit' })
}

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

watch(tab, () => { page.value = 0; load() })
watch(page, load)
onMounted(load)

useHead({ title: '優惠管理' })
</script>

<template>
  <div>
    <AdminPageHeader title="優惠管理" description="建立與維護優惠規則；券的發放數與核銷數在列上">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
        <AppButton size="sm" @click="form ? (form = null) : openCreate()">
          {{ form ? '收起表單' : '新增優惠' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <AppCard v-if="form" class="mb-6 p-5">
      <h2 class="mb-4 text-sm font-semibold">{{ editing === null ? '新增優惠' : `修改 #${editing}` }}</h2>
      <form class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" @submit.prevent="submit">
        <label class="flex flex-col gap-1 text-xs text-ink-muted sm:col-span-2 lg:col-span-3">
          名稱（會快照進訂單）
          <input v-model="form.name" required maxlength="128" class="field">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          類型{{ editing !== null ? '（建立後不可改）' : '' }}
          <select v-model="form.type" class="field" :disabled="editing !== null">
            <option v-for="(label, value) in TYPES" :key="value" :value="value">{{ label }}</option>
          </select>
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          折扣方式
          <select v-model="form.rule" class="field">
            <option value="FIXED_AMOUNT">固定金額</option>
            <option value="PERCENTAGE">比例</option>
          </select>
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          {{ form.rule === 'PERCENTAGE' ? '折扣率（0.2 = 折 20%）' : '折抵金額' }}
          <input v-model="form.value" type="number" step="0.01" min="0" required class="field figure">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          門檻金額（0 = 無門檻）
          <input v-model="form.threshold" type="number" min="0" class="field figure">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          折抵上限{{ form.rule === 'PERCENTAGE' ? '（比例折扣必填）' : '' }}
          <input v-model="form.maxDiscount" type="number" min="0" class="field figure" placeholder="不限">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          兌換所需積分（空白 = 不開放兌換）
          <input v-model="form.pointCost" type="number" min="1" class="field figure" placeholder="不開放">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          開始
          <input v-model="form.startAt" type="datetime-local" required class="field figure">
        </label>
        <label class="flex flex-col gap-1 text-xs text-ink-muted">
          結束
          <input v-model="form.endAt" type="datetime-local" required class="field figure">
        </label>
        <label class="flex items-center gap-2 self-end text-sm">
          <input v-model="form.enabled" type="checkbox" class="accent-[var(--accent)]">
          啟用
        </label>
        <div class="flex gap-2 sm:col-span-2 lg:col-span-3">
          <AppButton type="submit" :disabled="saving">{{ saving ? '儲存中⋯' : '儲存' }}</AppButton>
          <AppButton variant="ghost" @click="form = null">取消</AppButton>
        </div>
      </form>
    </AppCard>

    <AdminTabs v-model="tab" :tabs="TYPE_TABS" />

    <p
      v-if="error"
      class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="loading" class="mt-4 flex flex-col gap-2">
      <SkeletonBlock class="h-20" />
      <SkeletonBlock class="h-20" />
    </div>

    <EmptyState v-else-if="rows.length === 0" class="mt-6" title="這個類型下沒有優惠。">
      <AppButton size="sm" @click="openCreate">新增優惠</AppButton>
    </EmptyState>

    <template v-else>
      <p class="figure mt-4 text-xs text-ink-faint">
        共 {{ total.toLocaleString() }} 筆 · 第 {{ page + 1 }} / {{ totalPages }} 頁
      </p>
      <ul class="mt-2 flex flex-col gap-2">
        <li v-for="promotion in rows" :key="promotion.id">
          <AppCard class="flex flex-wrap items-center gap-x-5 gap-y-3 p-4" :muted="!promotion.enabled">
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2">
                <span class="figure text-xs text-ink-faint">#{{ promotion.id }}</span>
                <span class="text-sm font-medium">{{ promotion.name }}</span>
                <span class="rounded-sm bg-sunken px-1.5 py-0.5 text-[11px] text-ink-muted">
                  {{ TYPES[promotion.type] ?? promotion.type }}
                </span>
                <span
                  v-if="!promotion.enabled"
                  class="rounded-sm bg-danger-soft px-1.5 py-0.5 text-[11px] font-semibold text-danger"
                >
                  已停用
                </span>
              </div>
              <p class="mt-1 flex flex-wrap gap-x-4 gap-y-0.5 text-xs text-ink-muted">
                <span class="text-ink">{{ describe(promotion) }}</span>
                <span class="figure">{{ formatDate(promotion.startAt) }} – {{ formatDate(promotion.endAt) }}</span>
                <span v-if="promotion.pointCost" class="figure">{{ promotion.pointCost }} 點兌換</span>
              </p>
            </div>

            <div v-if="promotion.type === 'COUPON' || promotion.issuedCoupons > 0" class="figure shrink-0 text-right text-xs text-ink-muted">
              <p><span class="text-base font-bold text-ink">{{ promotion.issuedCoupons.toLocaleString() }}</span> 張已發</p>
              <p><span class="text-base font-bold text-ok">{{ promotion.usedCoupons.toLocaleString() }}</span> 張已用</p>
            </div>

            <div class="flex shrink-0 items-center gap-2">
              <AppButton variant="secondary" size="sm" @click="openEdit(promotion)">編輯</AppButton>
              <AppButton
                :variant="promotion.enabled ? 'danger' : 'primary'" size="sm"
                :disabled="busy === promotion.id" @click="toggle(promotion)"
              >
                {{ promotion.enabled ? '停用' : '啟用' }}
              </AppButton>
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
