<script setup lang="ts">
/**
 * 卡片。
 *
 * 用 1px 邊框而非陰影：這是一個要讓人看清楚數字的介面，
 * 密集列表上的陰影只會讓版面浮躁。
 */
withDefaults(defineProps<{
  interactive?: boolean
  muted?: boolean
  highlighted?: boolean
}>(), { interactive: false, muted: false, highlighted: false })
</script>

<template>
  <div
    class="rounded border bg-surface transition-colors"
    :class="[
      highlighted ? 'border-accent' : 'border-line',
      interactive ? 'hover:border-accent' : '',
      muted ? 'opacity-60' : '',
    ]"
  >
    <slot />
  </div>
</template>
