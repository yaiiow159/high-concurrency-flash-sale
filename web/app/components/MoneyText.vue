<script setup lang="ts">
/**
 * 金額。集中在一處是為了千分位一致與等寬數字。
 * 金額為 null 顯示破折號，不是 NT$ 0：「還不知道多少錢」與「不用錢」是兩件事。
 */
withDefaults(defineProps<{
  amount: number | null | undefined
  size?: 'sm' | 'md' | 'lg' | 'xl'
  tone?: 'default' | 'accent' | 'danger' | 'muted'
}>(), { size: 'md', tone: 'default' })

const SIZES = {
  sm: 'text-sm',
  md: 'text-base',
  lg: 'text-xl font-extrabold',
  xl: 'text-3xl font-extrabold',
} as const

const TONES = {
  default: 'text-ink',
  accent: 'text-accent',
  danger: 'text-danger',
  muted: 'text-ink-muted',
} as const
</script>

<template>
  <span class="figure inline-flex items-baseline" :class="[SIZES[size], TONES[tone]]">
    <template v-if="amount == null">
      <span class="opacity-60">—</span>
    </template>
    <template v-else>
      <!-- 幣別縮小一階：數字才是使用者要看的東西 -->
      <span class="mr-0.5 text-[0.62em] font-bold">NT$</span>{{ amount.toLocaleString('zh-TW') }}
    </template>
  </span>
</template>
