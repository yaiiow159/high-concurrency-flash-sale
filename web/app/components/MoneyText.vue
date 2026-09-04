<script setup lang="ts">
/**
 * 金額。
 *
 * 集中在一處是為了兩件事：千分位一致，以及等寬數字。
 * 先前各頁自己呼叫 toLocaleString，有些地方漏了，
 * 於是同一個價格在列表與詳情頁長得不一樣。
 *
 * **金額為 null 顯示破折號，不是 NT$ 0。**
 * 「還不知道多少錢」與「不用錢」是兩件事，而先前的 `amount ?? 0`
 * 把前者顯示成後者——訂單還在非同步建立、或試算尚未回來時，
 * 畫面會閃一下「免費」。這在秒殺的輪詢畫面上特別明顯。
 */
withDefaults(defineProps<{
  amount: number | null | undefined
  size?: 'sm' | 'md' | 'lg' | 'xl'
  tone?: 'default' | 'accent' | 'danger' | 'muted'
}>(), { size: 'md', tone: 'default' })

const SIZES = {
  sm: 'text-sm',
  md: 'text-base',
  lg: 'text-xl font-semibold',
  xl: 'text-3xl font-bold',
} as const

const TONES = {
  default: 'text-ink',
  accent: 'text-accent',
  danger: 'text-danger',
  muted: 'text-ink-muted',
} as const
</script>

<template>
  <span class="figure" :class="[SIZES[size], TONES[tone]]">
    <template v-if="amount == null">
      <span class="opacity-60">—</span>
    </template>
    <template v-else>
      <span class="opacity-60">NT$</span>&nbsp;{{ amount.toLocaleString('zh-TW') }}
    </template>
  </span>
</template>
