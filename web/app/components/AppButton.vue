<script setup lang="ts">
/**
 * 按鈕。
 *
 * <p><b>高度固定，不是靠 padding 湊出來的。</b>
 * 先前只給 padding，於是有圖示的按鈕比純文字的高，
 * 並排時對不齊；手機上也常常小於 44px 的觸控目標下限。
 *
 * <p>主要按鈕用 `--cta` 而不是 `--accent`：連結需要的是可讀，
 * 按鈕需要的是行動感，兩者的最佳亮度本來就不同。
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
