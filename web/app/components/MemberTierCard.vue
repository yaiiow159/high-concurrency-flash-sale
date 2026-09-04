<script setup lang="ts">
import type { MemberProfileView } from '~/types/api'

/**
 * 會員等級卡。
 *
 * **電商的會員中心第一屏就是這張卡**，而它要在一眼之內回答三件事：
 * 我是什麼等級、我有多少點、我離下一級還差多少。
 * 三者缺一，使用者就得往下捲——而往下捲的那一刻，
 * 「升級」這個動機就消失了。
 *
 * 每個等級有自己的漸層。用實色的話四個等級只能靠文字分辨，
 * 而「等級」這件事的全部價值就是它要看起來不一樣。
 */
const props = defineProps<{
  profile: MemberProfileView | null
  loading?: boolean
}>()

/**
 * 等級的視覺。
 *
 * 銅／銀／金／白金各自的色系是真實世界的金屬色，不是隨便挑的——
 * 使用者不必看文字就知道哪一個比較高階，而那是這套視覺唯一要做的事。
 */
const TIER_STYLES: Record<string, { from: string, to: string, ink: string }> = {
  BRONZE: { from: '#8a6242', to: '#5e412b', ink: '#f6ece4' },
  SILVER: { from: '#9aa7ae', to: '#6d7c85', ink: '#f7fafb' },
  GOLD: { from: '#d4a548', to: '#a3762a', ink: '#fffaf0' },
  PLATINUM: { from: '#5c6b78', to: '#2b3742', ink: '#eaf2f7' },
}

const style = computed(() => {
  const tier = TIER_STYLES[props.profile?.tier ?? 'BRONZE'] ?? TIER_STYLES.BRONZE!
  return {
    background: `linear-gradient(135deg, ${tier.from} 0%, ${tier.to} 100%)`,
    color: tier.ink,
  }
})
</script>

<template>
  <SkeletonBlock v-if="loading" class="h-44 rounded-[--radius]" />

  <div
    v-else-if="profile"
    class="relative overflow-hidden rounded-[--radius] p-6 shadow-[--lift]"
    :style="style"
  >
    <!-- 裝飾光暈。pointer-events-none 不可省，否則它會蓋住底下的文字選取 -->
    <span
      class="pointer-events-none absolute -right-16 -top-16 h-52 w-52 rounded-full
             bg-white/10 blur-2xl"
      aria-hidden="true"
    />

    <div class="relative flex flex-wrap items-start justify-between gap-x-8 gap-y-5">
      <div>
        <p class="text-[11px] font-semibold uppercase tracking-[0.14em] opacity-70">
          Membership
        </p>
        <p class="mt-1 text-2xl font-bold tracking-tight">{{ profile.tierName }}</p>
        <p class="mt-1.5 text-xs opacity-80">
          積分回饋 <span class="figure font-semibold">{{ profile.multiplier }}×</span>
          ・累計消費 <span class="figure">NT$ {{ profile.cumulativeSpend.toLocaleString() }}</span>
        </p>
      </div>

      <div class="text-right">
        <p class="text-[11px] font-semibold uppercase tracking-[0.14em] opacity-70">
          Points
        </p>
        <p class="figure mt-1 text-3xl font-bold leading-none tracking-tight">
          {{ profile.pointBalance.toLocaleString() }}
        </p>
        <!--
          負餘額要說清楚，而且不能只是一個紅色數字。
          使用者看到「-50」的第一個反應是「系統壞了」，
          而事實是他退貨之後把已經用掉的點還回來
        -->
        <p v-if="profile.inDebt" class="mt-1 text-xs font-medium">
          退貨已扣回，補足後才能再兌換
        </p>
      </div>
    </div>

    <div class="relative mt-6">
      <div class="flex items-baseline justify-between text-xs">
        <span class="opacity-80">
          <template v-if="profile.nextTierName">
            距離{{ profile.nextTierName }}還差
            <span class="figure font-semibold">
              NT$ {{ profile.amountToNextTier.toLocaleString() }}
            </span>
          </template>
          <template v-else>已達最高等級</template>
        </span>
        <span class="figure opacity-70">{{ profile.progressToNextTier }}%</span>
      </div>
      <!-- 軌道永遠在，即使進度是 0——消失的軌道看起來像渲染壞了 -->
      <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-black/25">
        <div
          class="h-full rounded-full bg-white/85 transition-[width] duration-700"
          :style="{ width: `${profile.progressToNextTier}%` }"
        />
      </div>
    </div>
  </div>
</template>
