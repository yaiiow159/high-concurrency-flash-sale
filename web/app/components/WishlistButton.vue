<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

const props = defineProps<{ productId: number, size?: 'sm' | 'md' }>()

const { isWishlisted, toggle } = useWishlist()
const busy = ref(false)
const toast = useToast()
const auth = useAuthStore()

const active = computed(() => isWishlisted(props.productId))

async function onClick() {
  if (busy.value) {
    return
  }
  if (!auth.isAuthenticated) {
    toast.info('登入後就能收藏商品', { label: '登入', to: '/account' })
    return
  }
  busy.value = true
  const adding = !active.value
  try {
    await toggle(props.productId)
    if (adding) {
      toast.success('已加入收藏', { label: '查看', to: '/wishlist' })
    }
  } catch (cause) {
    // 不放 title 屬性：觸控裝置上它永遠不會出現
    toast.error(cause instanceof Error && cause.message ? cause.message : '收藏失敗，請稍後再試')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <button
    type="button"
    class="grid place-items-center rounded-full border transition-colors"
    :class="[
      props.size === 'sm' ? 'h-8 w-8 text-sm' : 'h-10 w-10 text-base',
      active
        ? 'border-danger/40 bg-danger-soft text-danger'
        : 'border-line bg-surface text-ink-faint hover:border-danger/40 hover:text-danger',
    ]"
    :aria-pressed="active"
    :aria-label="active ? '取消收藏' : '加入收藏'"
    :title="active ? '取消收藏' : '加入收藏'"
    :disabled="busy"
    @click.stop.prevent="onClick"
  >
    <!-- 實心 / 空心以填色區分，不用兩個不同的圖示——換圖示會讓按鈕在切換時跳動 -->
    <svg viewBox="0 0 24 24" class="h-[1.15em] w-[1.15em]" aria-hidden="true">
      <path
        d="M12 21s-7.5-4.6-9.5-9A5.2 5.2 0 0 1 12 6.5 5.2 5.2 0 0 1 21.5 12c-2 4.4-9.5 9-9.5 9Z"
        :fill="active ? 'currentColor' : 'none'"
        stroke="currentColor"
        stroke-width="1.6"
        stroke-linejoin="round"
      />
    </svg>
  </button>
</template>
