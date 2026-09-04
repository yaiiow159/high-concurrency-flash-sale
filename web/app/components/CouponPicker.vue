<script setup lang="ts">
import type { CouponView } from '~/types/api'

/**
 * 優惠券選擇。
 *
 * **不顯示「這張券會折多少」。** 那個數字要由伺服器試算才知道
 * （門檻、上限、與其他優惠的疊加順序都會影響），
 * 前端自己算出來的版本遲早會與結帳金額對不上，
 * 而使用者只會相信他先看到的那一個。這裡只描述規則本身。
 */
defineProps<{
  coupons: CouponView[]
  loading?: boolean
}>()

const selected = defineModel<number | null>({ default: null })

/** 券的規則描述。門檻為 0 時不提，「滿 0 元」是雜訊。 */
function describe(coupon: CouponView): string {
  const discount = coupon.rule === 'PERCENTAGE'
    ? `折 ${Math.round(coupon.value * 100)}%${coupon.maxDiscount ? `（最多 ${coupon.maxDiscount.toLocaleString()}）` : ''}`
    : `折 ${coupon.value.toLocaleString()}`
  return coupon.threshold > 0
    ? `滿 ${coupon.threshold.toLocaleString()} ${discount}`
    : discount
}

function formatExpiry(iso: string): string {
  return new Date(iso).toLocaleDateString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  })
}
</script>

<template>
  <section aria-labelledby="coupon-heading">
    <h2 id="coupon-heading" class="eyebrow mb-3">優惠券</h2>

    <SkeletonBlock v-if="loading" class="h-16" />

    <p v-else-if="coupons.length === 0" class="text-sm text-ink-muted">
      目前沒有可用的優惠券。
    </p>

    <div v-else class="flex flex-col gap-2">
      <label
        v-for="coupon in coupons"
        :key="coupon.id"
        class="flex cursor-pointer items-start gap-3 rounded-sm border p-4 text-sm
               transition-colors"
        :class="coupon.id === selected
          ? 'border-accent bg-accent-soft'
          : 'border-line hover:border-line-strong'"
      >
        <input
          v-model="selected" type="radio" name="coupon"
          :value="coupon.id" class="mt-1 accent-[var(--accent)]"
        >
        <span class="min-w-0">
          <span class="font-medium">{{ coupon.name }}</span>
          <span class="mt-1 block text-ink-muted">{{ describe(coupon) }}</span>
          <span class="figure mt-1 block text-xs text-ink-faint">
            {{ formatExpiry(coupon.expiresAt) }} 到期
          </span>
        </span>
      </label>

      <label
        class="flex cursor-pointer items-center gap-3 rounded-sm border p-4 text-sm
               transition-colors"
        :class="selected === null
          ? 'border-accent bg-accent-soft'
          : 'border-line hover:border-line-strong'"
      >
        <input
          v-model="selected" type="radio" name="coupon"
          :value="null" class="accent-[var(--accent)]"
        >
        <span class="text-ink-muted">不使用優惠券</span>
      </label>
    </div>
  </section>
</template>
