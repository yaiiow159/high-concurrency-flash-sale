<script setup lang="ts">
/**
 * 空狀態。
 *
 * 每個空狀態都必須給一條出路。只寫「沒有資料」是把使用者留在死路上，
 * 而他其實只是還沒開始。
 */
defineProps<{ title: string; hint?: string }>()
</script>

<template>
  <div class="rounded border border-dashed border-line px-6 py-14 text-center">
    <p class="text-ink-muted">{{ title }}</p>
    <p v-if="hint" class="mt-1 text-sm text-ink-faint">{{ hint }}</p>
    <div class="mt-5">
      <slot />
    </div>
  </div>
</template>
