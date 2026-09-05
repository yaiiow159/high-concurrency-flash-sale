<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { ClaimableCouponView } from '~/types/api'

/**
 * 領券中心。
 *
 * <p>促銷引擎（ADR-0013）本來就完整，但券只能由管理員發放——
 * 使用者沒有任何地方可以拿到券，於是整套機制在前台是看不見的。
 *
 * <p><b>不做 SSR</b>：「我領過哪些」是個人資料，進了被快取的 HTML
 * 就等於發給下一個訪客。
 */
const auth = useAuthStore()
const { request } = useApi()

const coupons = ref<ClaimableCouponView[]>([])
const loading = ref(false)
const claiming = ref<number | null>(null)
const error = ref<string | null>(null)

async function load() {
  if (!auth.isAuthenticated) {
    return
  }
  loading.value = true
  error.value = null
  try {
    coupons.value = await request<ClaimableCouponView[]>(
      '/api/v1/coupons/claimable', { authenticated: true })
  } catch (cause) {
    error.value = errorMessage(cause, '無法載入優惠券')
    coupons.value = []
  } finally {
    loading.value = false
  }
}

/**
 * 領一張。
 *
 * 後端對重複領取回 `claimed: false` 而不是錯誤——使用者連點兩下、
 * 兩個分頁各按一次都是正常操作，而他要的答案兩種情況都一樣：
 * 「這張券在你手上了」。因此這裡兩種結果都走同一條路：重新載入。
 */
async function claim(promotionId: number) {
  claiming.value = promotionId
  error.value = null
  try {
    await request<{ claimed: boolean }>(
      `/api/v1/coupons/${promotionId}/claim`, { method: 'POST', authenticated: true })
    await load()
  } catch (cause) {
    error.value = errorMessage(cause, '領取失敗')
  } finally {
    claiming.value = null
  }
}

onMounted(load)
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    void load()
  }
})

/** 折抵說明。百分比與定額的講法不同，混在一起講會看不懂。 */
function describe(coupon: ClaimableCouponView): string {
  const threshold = coupon.threshold > 0
    ? `滿 ${coupon.threshold.toLocaleString()} `
    : ''
  if (coupon.rule === 'PERCENTAGE') {
    const off = Math.round((1 - coupon.value) * 100)
    const cap = coupon.maxDiscount === null
      ? ''
      : `（最多折 ${coupon.maxDiscount.toLocaleString()}）`
    return `${threshold}折 ${off}%${cap}`
  }
  return `${threshold}折 ${coupon.value.toLocaleString()} 元`
}

useHead({ title: '領券中心' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Coupons"
      title="領券中心"
      description="領到的券會出現在結帳頁，符合門檻時自動可選。"
    />

    <AuthPanel v-if="!auth.isAuthenticated" />

    <template v-else>
      <p v-if="error" class="mb-4 text-sm text-danger">{{ error }}</p>

      <div v-if="loading && coupons.length === 0" class="flex flex-col gap-3">
        <SkeletonBlock v-for="n in 3" :key="n" class="h-24" />
      </div>

      <ul v-else-if="coupons.length > 0" class="grid gap-3 sm:grid-cols-2">
        <li v-for="coupon in coupons" :key="coupon.promotionId">
          <AppCard class="flex items-center justify-between gap-4 p-4">
            <div class="min-w-0">
              <p class="truncate font-medium">{{ coupon.name }}</p>
              <p class="mt-0.5 text-sm text-accent">{{ describe(coupon) }}</p>
              <p class="mt-1 text-xs text-ink-faint">
                至 {{ new Date(coupon.endAt).toLocaleDateString('zh-TW') }}
              </p>
            </div>
            <AppButton
              size="sm"
              :variant="coupon.claimed ? 'secondary' : 'primary'"
              :disabled="coupon.claimed || claiming === coupon.promotionId"
              @click="claim(coupon.promotionId)"
            >
              {{ coupon.claimed ? '已領取' : (claiming === coupon.promotionId ? '領取中⋯' : '領取') }}
            </AppButton>
          </AppCard>
        </li>
      </ul>

      <EmptyState v-else title="目前沒有可領的優惠券。" hint="活動開始時會出現在這裡。" />
    </template>
  </div>
</template>
