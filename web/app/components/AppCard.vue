<script setup lang="ts">
/** 卡片。靜止時就有一層很淡的投影，亮色介面靠陰影而不是邊框產生層次。 */
withDefaults(defineProps<{
  interactive?: boolean
  muted?: boolean
  highlighted?: boolean
}>(), { interactive: false, muted: false, highlighted: false })
</script>

<template>
  <div
    class="rounded border bg-surface shadow-rest transition-[border-color,box-shadow,transform] duration-200"
    :class="[
      highlighted ? 'border-accent' : 'border-line',
      interactive ? 'hover:-translate-y-0.5 hover:border-line-strong hover:shadow-lift' : '',
      muted ? 'opacity-60' : '',
    ]"
  >
    <slot />
  </div>
</template>
