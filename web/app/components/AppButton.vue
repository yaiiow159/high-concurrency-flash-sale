<script setup lang="ts">
/**
 * 按鈕。
 *
 * 存在的理由不是「少寫幾個 class」，而是先前每一頁各自拼 class，
 * 結果同一個「主要動作」在不同頁面有三種內距與兩種圓角。
 * 樣式集中在一處，視覺一致就不必靠自律維持。
 */
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

const base = 'inline-flex items-center justify-center gap-2 rounded font-medium '
  + 'transition-colors disabled:cursor-not-allowed disabled:opacity-45'

const variants = {
  primary: 'bg-accent text-on-accent hover:bg-accent-hover',
  secondary: 'border border-line-strong text-ink hover:border-accent hover:text-accent',
  ghost: 'text-accent hover:bg-accent-soft',
  danger: 'text-danger hover:bg-danger-soft',
} as const

const sizes = {
  sm: 'px-3 py-1.5 text-sm',
  md: 'px-4 py-2.5 text-sm',
  lg: 'px-6 py-3.5 text-base',
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
