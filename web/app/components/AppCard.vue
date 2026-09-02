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
    class="rounded border bg-surface transition-[border-color,box-shadow] duration-200"
    :class="[
      highlighted ? 'border-accent' : 'border-line',
      /* 靜態列表維持純邊框；只有可點的卡片在 hover 時抬起，
         讓「可以按」這件事看得出來，而不必額外加一個箭頭圖示 */
      interactive ? 'hover:border-line-strong hover:shadow-lift' : '',
      muted ? 'opacity-60' : '',
    ]"
  >
    <slot />
  </div>
</template>
