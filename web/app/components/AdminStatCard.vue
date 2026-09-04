<script setup lang="ts">
/**
 * 後台總覽的一格待辦。
 *
 * **數字為 null 時顯示破折號，不是 0。**
 * 「查不到」與「沒有待辦」是完全不同的兩件事——把前者顯示成後者，
 * 維運人員會以為今天沒事，而真相是那支 API 掛了。
 * 這與 `MoneyText` 不把 null 顯示成 0 是同一個判斷。
 *
 * `alert` 為真時整張卡換色。用**邊框與底色**而不是只換數字顏色：
 * 這一頁的用途是掃過去找該處理的東西，而人掃版面時看的是塊，不是字。
 */
withDefaults(defineProps<{
  title: string
  value: number | null
  hint?: string
  to?: string
  loading?: boolean
  /** 需要注意。不代表錯誤——「有三筆待出貨」是正常的營運狀態 */
  alert?: boolean
}>(), { loading: false, alert: false })
</script>

<template>
  <component
    :is="to ? resolveComponent('NuxtLink') : 'div'"
    :to="to"
    class="group block rounded-[--radius] border p-5 transition-colors"
    :class="alert
      ? 'border-accent/45 bg-accent-soft'
      : 'border-line bg-surface hover:border-line-strong'"
  >
    <p class="eyebrow">{{ title }}</p>

    <SkeletonBlock v-if="loading" class="mt-2 h-9 w-16" />
    <p
      v-else
      class="figure mt-1.5 text-4xl font-bold leading-none tracking-tight"
      :class="alert ? 'text-accent' : ''"
    >
      {{ value === null ? '—' : value.toLocaleString() }}
    </p>

    <p v-if="hint" class="mt-2.5 text-xs leading-relaxed text-ink-muted">{{ hint }}</p>

    <p
      v-if="to"
      class="mt-3 text-xs text-ink-faint transition-colors group-hover:text-accent"
    >
      前往處理 →
    </p>
  </component>
</template>
