<script setup lang="ts">
/**
 * 金額。
 *
 * 集中在一處是為了兩件事：千分位一致，以及等寬數字。
 * 先前各頁自己呼叫 toLocaleString，有些地方漏了，
 * 於是同一個價格在列表與詳情頁長得不一樣。
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
    <span class="opacity-60">NT$</span>&nbsp;{{ (amount ?? 0).toLocaleString('zh-TW') }}
  </span>
</template>
