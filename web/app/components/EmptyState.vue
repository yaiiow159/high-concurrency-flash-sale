<script setup lang="ts">
/** 空狀態。每個空狀態都必須給一條出路。 */
defineProps<{ title: string; hint?: string }>()
</script>

<template>
  <div class="rounded border border-dashed border-line-strong bg-surface px-6 py-14 text-center">
    <div
      class="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-sunken text-ink-faint"
      aria-hidden="true"
    >
      <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.6">
        <path d="M4 7h16l-1.5 12h-13L4 7Z" stroke-linejoin="round" />
        <path d="M9 7V5a3 3 0 0 1 6 0v2" stroke-linecap="round" />
      </svg>
    </div>
    <p class="font-medium text-ink">{{ title }}</p>
    <p v-if="hint" class="mt-1 text-sm text-ink-muted">{{ hint }}</p>
    <div class="mt-5">
      <slot />
    </div>
  </div>
</template>
