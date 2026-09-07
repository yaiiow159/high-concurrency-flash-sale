<script setup lang="ts">
/** 頁面標題區。標題、說明、右側動作的相對位置全站一致。 */
defineProps<{ title: string; description?: string; eyebrow?: string }>()
</script>

<template>
  <header class="mb-6 flex flex-wrap items-end justify-between gap-4">
    <div>
      <p v-if="eyebrow" class="eyebrow mb-1.5">{{ eyebrow }}</p>
      <h1 class="text-xl font-extrabold tracking-tight sm:text-2xl">{{ title }}</h1>
      <p v-if="description" class="mt-1.5 max-w-prose text-sm text-ink-muted">
        {{ description }}
      </p>
    </div>
    <div class="flex items-center gap-3">
      <slot name="actions" />
    </div>
  </header>
</template>
