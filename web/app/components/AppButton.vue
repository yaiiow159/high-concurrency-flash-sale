<script setup lang="ts">
/** 按鈕。 */
withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
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
  + 'font-medium leading-none transition-[background-color,border-color,color] '
  + 'disabled:cursor-not-allowed disabled:opacity-40'

const variants = {
  primary: 'bg-cta text-white hover:bg-cta-hover active:bg-cta-active',
  secondary: 'border border-line-strong bg-surface text-ink '
    + 'hover:border-cta hover:text-cta',
  ghost: 'text-accent hover:bg-accent-soft',
  danger: 'text-danger hover:bg-danger-soft',
} as const

/* 高度取自觸控目標下限：手機上小於 44px 的按鈕會很難按準 */
const sizes = {
  sm: 'h-9 px-3.5 text-sm',
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
