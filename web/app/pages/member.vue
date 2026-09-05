<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useMembership } from '~/composables/useMembership'
import { useAuthStore } from '~/stores/auth'
import type {
  ExchangeableCouponView,
  MemberProfileView,
  PointTransactionView,
} from '~/types/api'

/**
 * 會員中心。
 *
 * 三個區塊，順序就是使用者關心的順序：
 * **我是什麼等級 → 我能換什麼 → 我的點是怎麼來的**。
 *
 * 把流水放最後而不是最前，是因為它只在「我的點怎麼少了」時才會被看——
 * 而那是少數情況。多數時候使用者來這裡是想知道能換什麼。
 */
const auth = useAuthStore()
const { profile, points, exchangeable, exchange } = useMembership()

const member = ref<MemberProfileView | null>(null)
const history = ref<PointTransactionView[]>([])
const coupons = ref<ExchangeableCouponView[]>([])
const loading = ref(true)
const exchanging = ref<number | null>(null)
const error = ref<string | null>(null)
const success = ref<string | null>(null)

/**
 * 三個查詢並行。它們互不相依，串起來只是把延遲加成三倍。
 *
 * 各自 catch：兌換清單掛掉不該讓等級卡也看不到。
 */
async function load() {
  loading.value = true
  const [me, tx, exchangeables] = await Promise.all([
    profile().catch(() => null),
    points().catch(() => []),
    exchangeable().catch(() => []),
  ])
  member.value = me
  history.value = tx
  coupons.value = exchangeables
  loading.value = false
}

async function redeem(coupon: ExchangeableCouponView) {
  if (!confirm(`用 ${coupon.pointCost} 點兌換「${coupon.name}」？\n\n兌換後積分不會退還。`)) {
    return
  }
  exchanging.value = coupon.promotionId
  error.value = null
  success.value = null
  try {
    const result = await exchange(coupon.promotionId)
    success.value = `已兌換「${result.promotionName}」，券號 ${result.couponCode}`
    // 重新載入而不是自己扣本地的餘額：伺服器是唯一的真實來源，
    // 而前端自己算的餘額會在兌換失敗重試後與實際值分岔
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '兌換失敗')
  } finally {
    exchanging.value = null
  }
}

/** 折抵規則的描述。與 CouponPicker 一致——同一個概念在兩處要長得一樣。 */
function describe(coupon: ExchangeableCouponView): string {
  const discount = coupon.rule === 'PERCENTAGE'
    ? `折 ${Math.round(coupon.value * 100)}%${coupon.maxDiscount ? `（最多 ${coupon.maxDiscount.toLocaleString()}）` : ''}`
    : `折 ${coupon.value.toLocaleString()}`
  return coupon.threshold > 0
    ? `滿 ${coupon.threshold.toLocaleString()} ${discount}`
    : discount
}

function formatTime(iso: string): string {
  return new Date(iso).toLocaleString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  })
}

onMounted(() => {
  if (auth.isAuthenticated) {
    void load()
  }
})
// 未登入時這一頁就地顯示登入面板，登入後不換頁，onMounted 不會再跑一次
watch(() => auth.isAuthenticated, (authenticated) => {
  if (authenticated) {
    void load()
  }
})

useHead({ title: '會員中心' })
</script>

<template>
  <div>
    <PageHeader eyebrow="Membership" title="會員中心" />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div v-else class="flex flex-col gap-8">
      <MemberTierCard :profile="member" :loading="loading" />

      <p
        v-if="error"
        class="rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
        role="alert"
      >
        {{ error }}
      </p>
      <p
        v-if="success"
        class="rounded-sm border border-ok/40 bg-ok-soft p-3 text-sm"
        :style="{ color: 'var(--ok)' }"
        role="status"
      >
        {{ success }}
      </p>

      <section aria-labelledby="exchange-heading">
        <div class="mb-3 flex items-baseline justify-between gap-4">
          <h2 id="exchange-heading" class="eyebrow">積分兌換</h2>
          <NuxtLink
            to="/checkout"
            class="text-xs text-ink-faint transition-colors hover:text-accent"
          >
            換到的券會出現在結帳頁 →
          </NuxtLink>
        </div>

        <div v-if="loading" class="grid gap-3 sm:grid-cols-2">
          <SkeletonBlock class="h-28" />
          <SkeletonBlock class="h-28" />
        </div>

        <EmptyState v-else-if="coupons.length === 0" title="目前沒有開放兌換的優惠券。" />

        <ul v-else class="grid gap-3 sm:grid-cols-2">
          <li v-for="coupon in coupons" :key="coupon.promotionId">
            <AppCard
              class="flex h-full flex-col justify-between gap-4 p-5"
              :class="coupon.affordable ? '' : 'opacity-60'"
            >
              <div>
                <p class="font-medium">{{ coupon.name }}</p>
                <p class="mt-1 text-sm text-ink-muted">{{ describe(coupon) }}</p>
              </div>
              <div class="flex items-end justify-between gap-3">
                <p>
                  <span class="figure text-xl font-bold">
                    {{ coupon.pointCost.toLocaleString() }}
                  </span>
                  <span class="ml-1 text-xs text-ink-muted">點</span>
                </p>
                <!--
                  換不起時按鈕禁用而不是隱藏：使用者要看得到自己還差多少，
                  而隱藏會讓他以為這張券不存在
                -->
                <AppButton
                  size="sm"
                  :disabled="!coupon.affordable || exchanging === coupon.promotionId"
                  @click="redeem(coupon)"
                >
                  {{ exchanging === coupon.promotionId
                    ? '兌換中⋯'
                    : coupon.affordable ? '兌換' : '點數不足' }}
                </AppButton>
              </div>
            </AppCard>
          </li>
        </ul>
      </section>

      <section aria-labelledby="history-heading">
        <h2 id="history-heading" class="eyebrow mb-3">積分紀錄</h2>

        <div v-if="loading" class="flex flex-col gap-2">
          <SkeletonBlock class="h-12" />
          <SkeletonBlock class="h-12" />
        </div>

        <EmptyState
          v-else-if="history.length === 0"
          title="還沒有任何積分紀錄。"
          hint="訂單送達後會自動入帳。"
        />

        <AppCard v-else class="divide-y divide-line px-5">
          <div
            v-for="entry in history"
            :key="entry.id"
            class="flex items-center justify-between gap-4 py-3.5"
          >
            <div class="min-w-0">
              <p class="text-sm">{{ entry.reasonName }}</p>
              <p class="figure mt-0.5 truncate text-xs text-ink-faint">
                {{ entry.refNo }}・{{ formatTime(entry.createdAt) }}
              </p>
            </div>
            <div class="shrink-0 text-right">
              <!-- 正負用顏色與符號雙重標示：只靠顏色的話色盲使用者分不出來 -->
              <p
                class="figure font-semibold"
                :class="entry.delta > 0 ? 'text-accent' : 'text-danger'"
              >
                {{ entry.delta > 0 ? '+' : '' }}{{ entry.delta.toLocaleString() }}
              </p>
              <p class="figure mt-0.5 text-xs text-ink-faint">
                餘 {{ entry.balanceAfter.toLocaleString() }}
              </p>
            </div>
          </div>
        </AppCard>
      </section>
    </div>
  </div>
</template>
