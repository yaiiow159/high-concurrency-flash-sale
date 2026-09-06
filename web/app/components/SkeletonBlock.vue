<script setup lang="ts">
/** 骨架屏。 */
withDefaults(defineProps<{
  /** Tailwind 高度類別，例如 h-4、h-40 */
  height?: string
  width?: string
  rounded?: boolean
}>(), { height: 'h-4', width: 'w-full', rounded: false })
</script>

<template>
  <div
    class="animate-pulse bg-sunken"
    :class="[height, width, rounded ? 'rounded' : 'rounded-sm']"
    aria-hidden="true"
  />
</template>
