<script setup lang="ts">
const props = defineProps<{
  available: number
  total: number
}>()

const soldRatio = computed(() => {
  if (props.total <= 0) return 0
  return Math.min(1, Math.max(0, (props.total - props.available) / props.total))
})

const soldOut = computed(() => props.available <= 0)
</script>

<template>
  <div>
    <div class="mb-1.5 flex items-baseline justify-between text-sm">
      <span class="text-[var(--ink-muted)]">剩餘庫存</span>
      <span class="tabular font-mono font-semibold" :class="soldOut ? 'text-[var(--danger)]' : ''">
        {{ available }} / {{ total }}
      </span>
    </div>
    <div
      class="h-2 overflow-hidden rounded-full bg-slate-200"
      role="progressbar"
      :aria-valuenow="Math.round(soldRatio * 100)"
      aria-valuemin="0"
      aria-valuemax="100"
      aria-label="已售出比例"
    >
      <div
        class="h-full rounded-full transition-[width] duration-500"
        :class="soldOut ? 'bg-[var(--danger)]' : 'bg-[var(--accent)]'"
        :style="{ width: `${soldRatio * 100}%` }"
      />
    </div>
  </div>
</template>
