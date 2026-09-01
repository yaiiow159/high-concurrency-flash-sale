<script setup lang="ts">
/**
 * 開賣倒數。
 *
 * **時間一律來自 `serverNow`，絕不用 `Date.now()`。**
 * 客戶端時鐘可能偏差數分鐘：時鐘快的使用者會提早狂打 API，
 * 慢的則錯過開賣。校正邏輯見 `useServerTime`。
 */
const props = defineProps<{
  startAt: string
  endAt: string
  /** 校正後的伺服器時間 */
  serverNow: () => number
}>()

const emit = defineEmits<{ started: [] }>()

type Phase = 'before' | 'running' | 'ended'

const remainingMillis = ref(0)
const phase = ref<Phase>('before')

let timer: ReturnType<typeof setInterval> | null = null
let startedEmitted = false

function refresh(): void {
  const now = props.serverNow()
  const start = new Date(props.startAt).getTime()
  const end = new Date(props.endAt).getTime()

  if (now < start) {
    phase.value = 'before'
    remainingMillis.value = start - now
    return
  }
  if (now >= end) {
    phase.value = 'ended'
    remainingMillis.value = 0
    return
  }

  phase.value = 'running'
  remainingMillis.value = end - now
  if (!startedEmitted) {
    startedEmitted = true
    emit('started')
  }
}

/**
 * 每 250ms 更新一次而非每秒。
 *
 * 若剛好每秒更新，倒數會在跨秒時看起來卡頓或跳號；
 * 用更短的間隔取樣，顯示的秒數才會準時翻動。
 */
onMounted(() => {
  refresh()
  timer = setInterval(refresh, 250)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

const parts = computed(() => {
  const total = Math.max(0, Math.floor(remainingMillis.value / 1000))
  return {
    hours: String(Math.floor(total / 3600)).padStart(2, '0'),
    minutes: String(Math.floor((total % 3600) / 60)).padStart(2, '0'),
    seconds: String(total % 60).padStart(2, '0'),
  }
})

const label = computed(() => {
  if (phase.value === 'before') return '距開賣'
  if (phase.value === 'running') return '距結束'
  return '活動已結束'
})

defineExpose({ phase })
</script>

<template>
  <div class="flex items-baseline gap-3">
    <span class="text-sm text-[var(--ink-muted)]">{{ label }}</span>
    <div v-if="phase !== 'ended'" class="tabular font-mono text-2xl font-semibold tracking-tight">
      <span>{{ parts.hours }}</span>
      <span class="mx-0.5 text-[var(--ink-muted)]">:</span>
      <span>{{ parts.minutes }}</span>
      <span class="mx-0.5 text-[var(--ink-muted)]">:</span>
      <span>{{ parts.seconds }}</span>
    </div>
  </div>
</template>
