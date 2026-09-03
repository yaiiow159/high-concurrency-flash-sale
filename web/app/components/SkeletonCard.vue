<script setup lang="ts">
/**
 * 商品／訂單卡片的骨架。
 *
 * <p>形狀刻意與真實卡片一致（同樣的圓角、內距、行高），
 * 否則內容到位時版面會跳動——那正是骨架屏要解決的問題，
 * 做得不像反而製造了它本來要避免的東西。
 */
withDefaults(defineProps<{ variant?: 'product' | 'row' }>(), { variant: 'product' })
</script>

<template>
  <AppCard v-if="variant === 'product'" class="overflow-hidden">
    <SkeletonBlock height="aspect-square" width="w-full" />
    <div class="flex flex-col gap-2 p-3.5 sm:p-4">
      <SkeletonBlock height="h-3" width="w-12" />
      <SkeletonBlock height="h-4" width="w-3/4" />
      <SkeletonBlock height="h-6" width="w-24" />
    </div>
  </AppCard>

  <AppCard v-else class="p-5">
    <div class="flex items-start justify-between gap-4">
      <div class="flex flex-1 flex-col gap-2">
        <SkeletonBlock height="h-4" width="w-1/2" />
        <SkeletonBlock height="h-3" width="w-1/3" />
      </div>
      <SkeletonBlock height="h-6" width="w-24" />
    </div>
  </AppCard>
</template>
