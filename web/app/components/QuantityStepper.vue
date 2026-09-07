<script setup lang="ts">
/** 數量選擇器：加減鈕 + 可直接輸入的數字。 */
const props = withDefaults(defineProps<{
  min?: number
  max?: number
  disabled?: boolean
}>(), { min: 1, max: 999, disabled: false })

const model = defineModel<number>({ default: 1 })

function clamp(value: number): number {
  if (!Number.isFinite(value)) return props.min
  return Math.min(props.max, Math.max(props.min, Math.floor(value)))
}

function step(delta: number) {
  model.value = clamp((model.value ?? props.min) + delta)
}

function onBlur(event: Event) {
  model.value = clamp(Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <div
    class="inline-flex h-10 items-stretch overflow-hidden rounded-sm border border-line-strong
           bg-surface"
    :class="disabled ? 'opacity-40' : ''"
  >
    <button
      type="button"
      class="grid w-10 place-items-center text-lg text-ink-muted transition-colors
             hover:bg-sunken hover:text-ink disabled:cursor-not-allowed"
      :disabled="disabled || (model ?? min) <= min"
      aria-label="減少數量"
      @click="step(-1)"
    >
      −
    </button>
    <input
      :value="model"
      type="number"
      :min="min"
      :max="max"
      inputmode="numeric"
      :disabled="disabled"
      class="figure w-14 border-x border-line-strong bg-surface text-center text-sm
             [appearance:textfield] [&::-webkit-inner-spin-button]:appearance-none
             [&::-webkit-outer-spin-button]:appearance-none"
      aria-label="數量"
      @blur="onBlur"
      @keyup.enter="onBlur"
    >
    <button
      type="button"
      class="grid w-10 place-items-center text-lg text-ink-muted transition-colors
             hover:bg-sunken hover:text-ink disabled:cursor-not-allowed"
      :disabled="disabled || (model ?? min) >= max"
      aria-label="增加數量"
      @click="step(1)"
    >
      +
    </button>
  </div>
</template>
