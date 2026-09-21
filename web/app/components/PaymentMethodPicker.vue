<script setup lang="ts">
import { PAYMENT_METHODS } from '~/utils/paymentMethods'
import type { PaymentMethod } from '~/types/api'

/** 付款方式。用整張卡片當點擊範圍——手機上瞄準一顆 16px 的圓點是在為難人。 */
defineProps<{ disabled?: boolean }>()
const model = defineModel<PaymentMethod>({ required: true })
</script>

<template>
  <fieldset :disabled="disabled" class="flex flex-col gap-2">
    <legend class="eyebrow mb-2">付款方式</legend>
    <label
      v-for="option in PAYMENT_METHODS"
      :key="option.value"
      class="flex cursor-pointer items-center gap-3 rounded-sm border px-3.5 py-2.5 text-sm transition-colors"
      :class="[
        model === option.value
          ? 'border-accent bg-accent-soft/50'
          : 'border-line hover:border-line-strong',
        disabled ? 'cursor-not-allowed opacity-50' : '',
      ]"
    >
      <input
        v-model="model" type="radio" name="payment-method" :value="option.value"
        class="accent-[var(--accent)]"
      >
      <span class="min-w-0">
        <span class="block font-medium">{{ option.label }}</span>
        <span class="block text-xs text-ink-muted">{{ option.hint }}</span>
      </span>
    </label>
  </fieldset>
</template>
