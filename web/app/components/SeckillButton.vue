<script setup lang="ts">
/**
 * 搶購按鈕——削峰漏斗的第 0 層。
 * 開賣瞬間加隨機抖動：所有人的倒數同時歸零、同時送出會製造尖銳的脈衝，
 * 打散幾百毫秒對使用者無感、對後端差別很大。按下後立即禁用擋連點。
 */
const props = withDefaults(defineProps<{
  started: boolean
  soldOut: boolean
  submitting: boolean
  authenticated: boolean
  /** 開賣前領到的資格（ADR-0028）。沒有就按不下去——按了也只會被 403 擋下 */
  qualified?: boolean
}>(), { qualified: true })

const emit = defineEmits<{ attempt: [] }>()

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
  () => props.started && jitterElapsed.value && !props.soldOut && !props.submitting
    && (props.qualified || !props.authenticated),
)

const text = computed(() => {
  if (!props.started) return '尚未開賣'
  if (props.soldOut) return '已售罄'
  if (props.submitting) return '處理中⋯'
  if (!props.authenticated) return '登入後搶購'
  if (!props.qualified) return '請先取得搶購資格'
  return '立即搶購'
})
</script>

<template>
  <button
    type="button"
    :disabled="!clickable"
    class="h-14 w-full rounded-sm px-6 text-lg font-extrabold tracking-wide transition
           disabled:cursor-not-allowed disabled:bg-sunken disabled:text-ink-faint
           disabled:shadow-none enabled:btn-promo"
    @click="emit('attempt')"
  >
    {{ text }}
  </button>
</template>
