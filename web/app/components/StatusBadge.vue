<script setup lang="ts">
/**
 * 狀態標籤。
 *
 * 顏色只分三種語意：進行中（中性）、成功（綠）、需要注意（赭）。
 * <b>語意色與品牌強調色刻意分開</b>——把「已付款」也塗成品牌青色，
 * 使用者就沒辦法一眼分辨「這是狀態」還是「這是可以按的東西」。
 */
const props = defineProps<{ status: string }>()

const LABELS: Record<string, string> = {
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  SHIPPED: '已出貨',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  FAILED: '建立失敗',
  PROCESSING: '處理中',
  READY: '待出貨',
  IN_TRANSIT: '運送中',
  DELIVERED: '已送達',
}

const TONES: Record<string, string> = {
  COMPLETED: 'ok',
  DELIVERED: 'ok',
  PAID: 'ok',
  CANCELLED: 'danger',
  FAILED: 'danger',
}

const label = computed(() => LABELS[props.status] ?? props.status)
const tone = computed(() => TONES[props.status] ?? 'neutral')
</script>

<template>
  <span
    class="inline-flex items-center rounded-sm border px-2 py-0.5 text-xs font-medium"
    :class="{
      'border-ok/40 bg-ok-soft text-ok': tone === 'ok',
      'border-danger/40 bg-danger-soft text-danger': tone === 'danger',
      'border-line bg-sunken text-ink-muted': tone === 'neutral',
    }"
  >
    {{ label }}
  </span>
</template>
