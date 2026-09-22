<script setup lang="ts">
/**
 * 全站回饋訊息的容器。放在頁首下方置中：手機底部已經被固定操作列佔走，
 * 放底部會蓋住「加入購物車」那顆按鈕——正是觸發它的那一顆。
 */
const { toasts, hold, release, dismiss } = useToast()
</script>

<template>
  <div
    class="pointer-events-none fixed inset-x-0 top-[7.25rem] z-50 flex flex-col items-center gap-2 px-4 sm:top-[9.25rem]"
    aria-live="polite"
  >
    <TransitionGroup
      enter-active-class="transition duration-200 ease-out"
      enter-from-class="-translate-y-2 opacity-0"
      leave-active-class="transition duration-150 ease-in absolute"
      leave-to-class="-translate-y-1 opacity-0"
      move-class="transition duration-200"
    >
      <div
        v-for="toast in toasts"
        :key="toast.id"
        class="pointer-events-auto flex max-w-md items-center gap-3 rounded-full bg-ink-inverse/95
               py-2.5 pl-3 pr-2 text-sm text-white shadow-lift backdrop-blur"
        :role="toast.tone === 'error' ? 'alert' : 'status'"
        @mouseenter="hold(toast.id)"
        @mouseleave="release(toast.id)"
      >
        <span
          class="grid h-6 w-6 shrink-0 place-items-center rounded-full"
          :class="{
            'bg-ok text-white': toast.tone === 'success',
            'bg-danger text-white': toast.tone === 'error',
            'bg-white/20 text-white': toast.tone === 'info',
          }"
          aria-hidden="true"
        >
          <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="3">
            <path v-if="toast.tone === 'success'" d="m5 12 5 5 9-10" stroke-linecap="round" stroke-linejoin="round" />
            <path v-else-if="toast.tone === 'error'" d="M12 7v6M12 17h.01" stroke-linecap="round" />
            <path v-else d="M12 11v6M12 7h.01" stroke-linecap="round" />
          </svg>
        </span>

        <p class="min-w-0 flex-1 leading-snug">{{ toast.message }}</p>

        <NuxtLink
          v-if="toast.action"
          :to="toast.action.to"
          class="shrink-0 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold transition-colors
                 hover:bg-white/25"
          @click="dismiss(toast.id)"
        >
          {{ toast.action.label }}
        </NuxtLink>

        <button
          type="button"
          class="grid h-7 w-7 shrink-0 place-items-center rounded-full text-white/60 transition-colors
                 hover:bg-white/15 hover:text-white"
          aria-label="關閉訊息"
          @click="dismiss(toast.id)"
        >
          <svg viewBox="0 0 24 24" class="h-3.5 w-3.5" fill="none" stroke="currentColor" stroke-width="2.4">
            <path d="m6 6 12 12M18 6 6 18" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
