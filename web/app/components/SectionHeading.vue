<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'

/** 區塊標題列：左側品牌色短槓 + 標題 + 右側「更多」。全站的區塊都長這樣。 */
withDefaults(defineProps<{
  title: string
  eyebrow?: string
  description?: string
  moreTo?: RouteLocationRaw | null
  moreLabel?: string
}>(), { eyebrow: undefined, description: undefined, moreTo: null, moreLabel: '更多' })
</script>

<template>
  <div class="mb-4 flex items-end justify-between gap-4">
    <div class="min-w-0">
      <p v-if="eyebrow" class="eyebrow mb-1 pl-3.5">{{ eyebrow }}</p>
      <h2 class="section-title">{{ title }}</h2>
      <p v-if="description" class="mt-1 pl-3.5 text-sm text-ink-muted">{{ description }}</p>
    </div>
    <slot name="aside">
      <NuxtLink
        v-if="moreTo"
        :to="moreTo"
        class="inline-flex shrink-0 items-center gap-0.5 whitespace-nowrap text-sm font-medium
               text-ink-muted transition-colors hover:text-accent"
      >
        {{ moreLabel }}
        <svg viewBox="0 0 24 24" class="h-4 w-4" fill="none" stroke="currentColor" stroke-width="2">
          <path d="m9 5 7 7-7 7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </NuxtLink>
    </slot>
  </div>
</template>
