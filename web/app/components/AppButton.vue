<script setup lang="ts">
/** 按鈕。 */
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'outline' | 'ghost' | 'danger'
  size?: 'sm' | 'md' | 'lg'
  block?: boolean
  disabled?: boolean
  type?: 'button' | 'submit'
}>(), {
  variant: 'primary',
  size: 'md',
  block: false,
  disabled: false,
  type: 'button',
})

const base = 'inline-flex select-none items-center justify-center gap-2 rounded-sm '
  + 'font-semibold leading-none transition-[background-color,border-color,color,box-shadow] '
  + 'disabled:cursor-not-allowed disabled:opacity-40'

const variants = {
  primary: 'bg-cta text-white shadow-[0_4px_12px_-4px_rgb(238_58_44/50%)] '
    + 'hover:bg-cta-hover active:bg-cta-active',
  /* 中性次要鈕：篩選、載入更多這類不該搶眼的動作 */
  secondary: 'border border-line-strong bg-surface text-ink hover:border-ink hover:bg-sunken',
  /* 品牌色外框：與主鈕並排時是「加入購物車」的那一顆 */
  outline: 'border border-cta bg-surface text-cta hover:bg-accent-soft',
  ghost: 'text-ink-muted hover:bg-sunken hover:text-ink',
  danger: 'text-danger hover:bg-danger-soft',
} as const

/* 高度取自觸控目標下限：手機上小於 44px 的按鈕會很難按準 */
const sizes = {
  sm: 'h-9 px-3.5 text-[13px]',
  md: 'h-11 px-5 text-sm',
  lg: 'h-12 px-6 text-base',
} as const
</script>

<template>
  <button
    :type="type"
    :disabled="disabled"
    :class="[base, variants[variant], sizes[size], block ? 'w-full' : '']"
  >
    <slot />
  </button>
</template>
