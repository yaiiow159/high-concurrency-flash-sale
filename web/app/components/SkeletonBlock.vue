<script setup lang="ts">
/**
 * 骨架屏。
 *
 * <p>取代「載入中⋯」這四個字。差別不只是好看：
 * 骨架屏保留了版面的形狀，內容到位時不會產生位移；
 * 而純文字提示會讓頁面在載入完成的瞬間整個重排，
 * 使用者剛要點的東西會跑掉。
 *
 * <p>動畫尊重 `prefers-reduced-motion`（在 main.css 全域關掉），
 * 前庭功能障礙的使用者不該為了一個載入提示而不舒服。
 */
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
