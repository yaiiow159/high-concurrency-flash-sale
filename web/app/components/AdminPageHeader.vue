<script setup lang="ts">
/**
 * 後台每一頁的標題列。與商店的 `PageHeader` 分開：後台的標題只是路標，
 * 佔掉半個畫面高度只會把真正要看的表格推到摺線下。
 */
defineProps<{
  title: string
  /** 一句話說明這一頁在做什麼。省略時不佔高度 */
  description?: string
}>()
</script>

<template>
  <header class="mb-5 flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
    <div class="min-w-0">
      <h1 class="text-xl font-extrabold tracking-tight sm:text-2xl">{{ title }}</h1>
      <p v-if="description" class="mt-1 text-sm text-ink-muted">{{ description }}</p>
    </div>
    <div class="flex shrink-0 items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
