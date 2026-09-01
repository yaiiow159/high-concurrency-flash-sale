<script setup lang="ts">
/**
 * 搶購按鈕 —— 削峰漏斗的第 0 層。
 *
 * 三個必須做對的細節：
 *
 * 1. **互動能力必須在開賣前就緒。** 若按鈕要等 hydration 完成才能點，
 *    開賣瞬間的第一波使用者會全部點空。
 * 2. **開賣瞬間加隨機抖動。** 所有人的倒數同時歸零、同時送出請求，
 *    會製造一個尖銳到不必要的脈衝——把它打散幾百毫秒，
 *    對使用者無感，對後端差別很大。
 * 3. **按下後立即禁用。** 使用者連點是常態，重複送出雖有 requestId 冪等兜底，
 *    但那是最後防線，不該當成第一道。
 */
const props = defineProps<{
  /** 活動時間窗口是否已開始 */
  started: boolean
  soldOut: boolean
  submitting: boolean
  authenticated: boolean
}>()

const emit = defineEmits<{ attempt: [] }>()

/** 開賣後的隨機延遲，讓瞬間湧入的請求散開。 */
const JITTER_MAX_MILLIS = 300

const jitterElapsed = ref(false)
let jitterTimer: ReturnType<typeof setTimeout> | null = null

watch(
  () => props.started,
  (started) => {
    if (!started) {
      jitterElapsed.value = false
      return
    }
    if (jitterTimer) return
    jitterTimer = setTimeout(
      () => { jitterElapsed.value = true },
      Math.random() * JITTER_MAX_MILLIS,
    )
  },
  { immediate: true },
)

onUnmounted(() => {
  if (jitterTimer) clearTimeout(jitterTimer)
})

const clickable = computed(
  () => props.started && jitterElapsed.value && !props.soldOut && !props.submitting,
)

const text = computed(() => {
  if (!props.started) return '尚未開賣'
  if (props.soldOut) return '已售罄'
  if (props.submitting) return '處理中⋯'
  if (!props.authenticated) return '登入後搶購'
  return '立即搶購'
})
</script>

<template>
  <button
    type="button"
    :disabled="!clickable"
    class="w-full rounded px-6 py-3 text-base font-bold text-white transition
           disabled:cursor-not-allowed disabled:bg-slate-300
           enabled:bg-[var(--danger)] enabled:hover:brightness-110
           focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2"
    @click="emit('attempt')"
  >
    {{ text }}
  </button>
</template>
