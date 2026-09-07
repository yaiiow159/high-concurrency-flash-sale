<script setup lang="ts">
/**
 * 後台總覽的一格待辦。數字為 null 時顯示破折號，不是 0：
 * 「查不到」與「沒有待辦」是兩件事，把前者顯示成後者，維運人員會以為今天沒事。
 * `alert` 為真時整張卡換色——人掃版面時看的是塊，不是字。
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
    class="group relative block overflow-hidden rounded border bg-surface p-5 shadow-rest
           transition-[border-color,box-shadow,transform]"
    :class="[
      alert ? 'border-accent/40' : 'border-line',
      to ? 'hover:-translate-y-0.5 hover:shadow-lift' : '',
    ]"
  >
    <!-- 左側色條：需要注意的卡片在一排卡片裡先被看到 -->
    <span
      class="absolute inset-y-0 left-0 w-1"
      :class="alert ? 'bg-promo' : 'bg-line'"
      aria-hidden="true"
    />
    <p class="eyebrow">{{ title }}</p>

    <SkeletonBlock v-if="loading" class="mt-2 h-9 w-16" />
    <p
      v-else
      class="figure mt-1.5 text-4xl font-extrabold leading-none tracking-tight"
      :class="alert ? 'text-accent' : 'text-ink'"
    >
      {{ value === null ? '—' : value.toLocaleString() }}
    </p>

    <p v-if="hint" class="mt-2.5 text-xs leading-relaxed text-ink-muted">{{ hint }}</p>

    <p
      v-if="to"
      class="mt-3 inline-flex items-center gap-0.5 text-xs font-medium text-ink-faint
             transition-colors group-hover:text-accent"
    >
      前往處理
      <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="2">
        <path d="m9 5 7 7-7 7" stroke-linecap="round" stroke-linejoin="round" />
      </svg>
    </p>
  </component>
</template>
