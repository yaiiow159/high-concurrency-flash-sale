<script setup lang="ts">
import { useReturns } from '~/composables/useReturns'
import { useAuthStore } from '~/stores/auth'
import type { ReturnReason, ReturnableView } from '~/types/api'

/**
 * 申請退貨。
 *
 * <p><b>可退數量問後端，不在這裡自己扣。</b>「審核中的退貨單也佔用額度」
 * 是領域規則；在前端複製一份，兩邊分岔時的症狀是
 * 「畫面說可以退，送出卻被拒絕」——畫面看起來完全正常，最難查。
 *
 * <p><b>「是否需要寄回」在按下送出之前就講明。</b>
 * 未出貨的訂單免寄回，已出貨的要等買家寄回才會退款。
 * 事後才告知等於讓人以為錢馬上會回來，那是客訴的標準起點。
 */
const route = useRoute()
const orderNo = route.params.orderNo as string

const auth = useAuthStore()
const { inspect, open } = useReturns()

const returnable = ref<ReturnableView | null>(null)
const loading = ref(true)
const loadError = ref<string | null>(null)
const submitting = ref(false)
const submitError = ref<string | null>(null)

/**
 * 冪等鍵：<b>送出前產生，只在成功後才作廢</b>。
 *
 * 與下單同一個手法（見 useCheckout）。逾時重送同一個值會拿回同一張退貨單；
 * 每次重試都換新值的話，使用者按兩次就會申請兩次退貨。
 */
let requestId: string | null = null

/** skuId → 這次要退的數量。0 代表不退這一項。 */
const quantities = reactive<Record<number, number>>({})

const reason = ref<ReturnReason>('CHANGED_MIND')
const reasonDetail = ref('')

/**
 * 原因的順序刻意把「商品有問題」放前面。
 *
 * 真正會退貨的人多半是收到瑕疵品，讓他們少捲一次；
 * 而「改變心意」放在最後也是一種輕微的提醒，
 * 不是為了勸退，是因為它的責任歸屬與運費規則不同。
 */
const REASONS: { value: ReturnReason, label: string, hint: string }[] = [
  { value: 'DEFECTIVE', label: '商品瑕疵或損壞', hint: '收到時已經有問題' },
  { value: 'NOT_AS_DESCRIBED', label: '與商品描述不符', hint: '實物與頁面說明不同' },
  { value: 'WRONG_ITEM', label: '出貨錯誤', hint: '寄來的不是我買的規格或品項' },
  { value: 'CHANGED_MIND', label: '改變心意', hint: '商品沒問題，但我不需要了' },
  { value: 'OTHER', label: '其他', hint: '請在下方說明' },
]

/** 選了「其他」卻不說明，客服無從判斷，等於一定會被退回來重問 */
const detailRequired = computed(() => reason.value === 'OTHER')

const selectedLines = computed(() =>
  (returnable.value?.lines ?? []).filter((line) => (quantities[line.skuId] ?? 0) > 0),
)

const refundEstimate = computed(() =>
  selectedLines.value.reduce(
    (sum, line) => sum + line.unitPrice * (quantities[line.skuId] ?? 0), 0,
  ),
)

const canSubmit = computed(() =>
  selectedLines.value.length > 0
  && !submitting.value
  && (!detailRequired.value || reasonDetail.value.trim().length > 0),
)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const view = await inspect(orderNo)
    returnable.value = view
    // 預設全不選。預先勾滿看似貼心，但退貨是「只退一部分」比較常見的操作，
    // 而預選會讓人不小心把整張訂單都退掉
    for (const line of view.lines) {
      quantities[line.skuId] = 0
    }
  } catch (cause) {
    loadError.value = (cause as { message?: string }).message ?? '無法載入訂單'
  } finally {
    loading.value = false
  }
}

async function submit() {
  submitting.value = true
  submitError.value = null
  requestId ??= crypto.randomUUID()
  try {
    const created = await open(orderNo, {
      items: selectedLines.value.map((line) => ({
        skuId: line.skuId,
        quantity: quantities[line.skuId] ?? 0,
      })),
      reason: reason.value,
      reasonDetail: reasonDetail.value.trim() || undefined,
    }, requestId)
    requestId = null
    await navigateTo(`/returns/${created.returnNo}`)
  } catch (cause) {
    submitError.value = (cause as { message?: string }).message ?? '申請失敗'
    // 送出失敗通常代表可退數量已經變了（例如另一個分頁剛送出一張）。
    // 重新查一次，讓畫面回到真實狀態而不是停在一個已經不成立的表單上
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (auth.isAuthenticated) {
    load()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    load()
  }
})

useHead({ title: `申請退貨 ${orderNo}` })
</script>

<template>
  <div class="pb-action-bar">
    <PageHeader eyebrow="Return" title="申請退貨">
      <template #actions>
        <NuxtLink
          :to="`/orders/${orderNo}`"
          class="text-sm text-ink-muted transition-colors hover:text-ink"
        >
          ← 回訂單
        </NuxtLink>
      </template>
    </PageHeader>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div v-else-if="loading" class="flex flex-col gap-3">
      <SkeletonCard variant="row" />
      <SkeletonCard variant="row" />
    </div>

    <EmptyState v-else-if="loadError" :title="loadError">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/orders')">
        回訂單列表
      </AppButton>
    </EmptyState>

    <EmptyState
      v-else-if="returnable && !returnable.returnable"
      title="這張訂單目前無法申請退貨"
      :hint="returnable.reason ?? undefined"
    >
      <AppButton variant="secondary" size="sm" @click="navigateTo(`/orders/${orderNo}`)">
        回訂單
      </AppButton>
    </EmptyState>

    <div
      v-else-if="returnable"
      class="grid gap-8 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start lg:gap-12"
    >
      <div class="flex flex-col gap-8">
        <section aria-labelledby="items-heading">
          <h2 id="items-heading" class="eyebrow mb-3">選擇要退的品項</h2>
          <ul class="flex flex-col gap-2">
            <li v-for="line in returnable.lines" :key="line.skuId">
              <AppCard
                class="flex flex-wrap items-center justify-between gap-4 p-4"
                :class="{ 'opacity-50': line.returnableQuantity === 0 }"
              >
                <div class="min-w-0">
                  <p class="font-medium">{{ line.skuSnapshot }}</p>
                  <p class="mt-1 flex items-baseline gap-2 text-sm text-ink-muted">
                    <MoneyText :amount="line.unitPrice" size="sm" tone="muted" />
                    <span class="figure">共 {{ line.orderedQuantity }} 件</span>
                  </p>
                  <p
                    v-if="line.returnableQuantity === 0"
                    class="mt-1 text-xs text-ink-faint"
                  >
                    已全部申請過退貨
                  </p>
                </div>

                <label class="flex items-center gap-2 text-sm">
                  <span class="text-ink-muted">退貨數量</span>
                  <select
                    v-model.number="quantities[line.skuId]"
                    :disabled="line.returnableQuantity === 0"
                    class="figure h-11 rounded-sm border border-line bg-surface px-3
                           disabled:cursor-not-allowed"
                    :aria-label="`${line.skuSnapshot} 的退貨數量`"
                  >
                    <!-- 選單只列到可退上限。讓人選得到一個一定會被拒絕的數字，
                         是把驗證的責任推給伺服器再回頭怪使用者 -->
                    <option v-for="n in line.returnableQuantity + 1" :key="n - 1" :value="n - 1">
                      {{ n - 1 }}
                    </option>
                  </select>
                </label>
              </AppCard>
            </li>
          </ul>
        </section>

        <section aria-labelledby="reason-heading">
          <h2 id="reason-heading" class="eyebrow mb-3">退貨原因</h2>
          <div class="flex flex-col gap-2">
            <label
              v-for="option in REASONS"
              :key="option.value"
              class="flex cursor-pointer items-start gap-3 rounded-sm border p-3.5
                     text-sm transition-colors"
              :class="option.value === reason
                ? 'border-cta bg-accent-soft'
                : 'border-line hover:border-line-strong'"
            >
              <input
                v-model="reason"
                type="radio"
                name="return-reason"
                :value="option.value"
                class="mt-1 accent-[var(--cta)]"
              >
              <span>
                <span class="font-medium">{{ option.label }}</span>
                <span class="mt-0.5 block text-ink-muted">{{ option.hint }}</span>
              </span>
            </label>
          </div>

          <label class="mt-4 block">
            <span class="eyebrow">
              補充說明{{ detailRequired ? '（必填）' : '（選填）' }}
            </span>
            <textarea
              v-model="reasonDetail"
              rows="3"
              maxlength="512"
              class="mt-2 w-full rounded-sm border border-line bg-surface p-3 text-sm"
              placeholder="例如：螢幕左上角有一個亮點"
            />
          </label>
        </section>
      </div>

      <AppCard class="hidden p-5 lg:sticky lg:top-24 lg:block">
        <h2 class="eyebrow mb-3">預估退款金額</h2>
        <MoneyText :amount="refundEstimate" size="xl" />
        <p class="mt-2 text-xs text-ink-faint">
          依下單當時的單價計算，不受商家事後調價影響。
        </p>

        <!-- 需不需要寄回必須在送出之前就說。事後才講，
             使用者會以為錢馬上就回來了 -->
        <p class="mt-4 rounded-sm border border-line bg-sunken p-3 text-sm text-ink-muted">
          <template v-if="returnable.requiresGoodsReturn">
            這張訂單已出貨，核准後<b class="text-ink">需要把商品寄回</b>，
            我們收到並驗收後才會退款。
          </template>
          <template v-else>
            這張訂單尚未出貨，<b class="text-ink">不需要寄回商品</b>，
            核准後會直接退款。
          </template>
        </p>

        <AppButton class="mt-5" size="lg" block :disabled="!canSubmit" @click="submit">
          {{ submitting ? '送出中⋯' : '送出申請' }}
        </AppButton>

        <p
          v-if="submitError"
          class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
          role="alert"
        >
          {{ submitError }}
        </p>
      </AppCard>

      <!-- 手機：說明留在內容流，送出交給底部固定列 -->
      <p class="rounded-sm border border-line bg-sunken p-3 text-sm text-ink-muted lg:hidden">
        <template v-if="returnable.requiresGoodsReturn">
          這張訂單已出貨，核准後<b class="text-ink">需要把商品寄回</b>，
          我們收到並驗收後才會退款。
        </template>
        <template v-else>
          這張訂單尚未出貨，<b class="text-ink">不需要寄回商品</b>，核准後會直接退款。
        </template>
      </p>
      <p
        v-if="submitError"
        class="rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger lg:hidden"
        role="alert"
      >
        {{ submitError }}
      </p>
    </div>

    <StickyActionBar v-if="returnable?.returnable">
      <template #info>
        <MoneyText :amount="refundEstimate" size="lg" />
        <p class="mt-0.5 text-xs text-ink-faint">
          預估退款 · {{ selectedLines.length }} 項
        </p>
      </template>
      <template #action>
        <AppButton :disabled="!canSubmit" @click="submit">
          {{ submitting ? '送出中⋯' : '送出申請' }}
        </AppButton>
      </template>
    </StickyActionBar>
  </div>
</template>
