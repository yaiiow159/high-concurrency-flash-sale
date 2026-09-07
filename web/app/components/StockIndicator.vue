<script setup lang="ts">
/** 庫存進度條。顯示的是「已搶走多少」而不是「還剩多少」——前者才會催人下手。 */
const props = withDefaults(defineProps<{
  available: number
  total: number
  compact?: boolean
}>(), { compact: false })

const soldRatio = computed(() => {
  if (props.total <= 0) return 0
  return Math.min(1, Math.max(0, (props.total - props.available) / props.total))
})

const soldPercent = computed(() => Math.round(soldRatio.value * 100))
const soldOut = computed(() => props.available <= 0)
</script>

<template>
  <div>
    <div
      class="mb-1.5 flex items-baseline justify-between"
      :class="compact ? 'text-xs' : 'text-sm'"
    >
      <span class="font-semibold" :class="soldOut ? 'text-danger' : 'text-accent'">
        {{ soldOut ? '已售罄' : `已搶 ${soldPercent}%` }}
      </span>
      <span v-if="!compact" class="figure text-ink-muted">
        剩餘 {{ available.toLocaleString() }} / {{ total.toLocaleString() }}
      </span>
      <span v-else class="figure text-ink-faint">剩 {{ available.toLocaleString() }}</span>
    </div>
    <div
      class="overflow-hidden rounded-full bg-accent-soft"
      :class="compact ? 'h-1.5' : 'h-2'"
      role="progressbar"
      :aria-valuenow="soldPercent"
      aria-valuemin="0"
      aria-valuemax="100"
      aria-label="已售出比例"
    >
      <div
        class="h-full rounded-full transition-[width] duration-500"
        :class="soldOut ? 'bg-ink-faint' : 'bg-promo'"
        :style="{ width: `${soldRatio * 100}%` }"
      />
    </div>
  </div>
</template>
