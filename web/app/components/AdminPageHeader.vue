<script setup lang="ts">
/**
 * 後台每一頁的標題列。
 *
 * 與商店的 `PageHeader` 分開而不是加一個 variant：兩者的資訊密度目標相反。
 * 商店的標題是版面的一部分（大、有留白、有 eyebrow）；
 * 後台的標題只是一個路標——維運人員已經知道自己在哪一頁，
 * 佔掉半個畫面高度的標題只是把真正要看的表格推到摺線下。
 */
defineProps<{
  title: string
  /** 一句話說明這一頁在做什麼。省略時不佔高度 */
  description?: string
}>()
</script>

<template>
  <header class="mb-6 flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
    <div class="min-w-0">
      <h1 class="text-xl font-semibold tracking-tight sm:text-2xl">{{ title }}</h1>
      <p v-if="description" class="mt-1 text-sm text-ink-muted">{{ description }}</p>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
