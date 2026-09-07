<script setup lang="ts">
/**
 * 開賣倒數。時間一律來自 `serverNow`，絕不用 `Date.now()`：
 * 客戶端時鐘可能偏差數分鐘，快的會提早狂打 API、慢的會錯過開賣。
 */
const props = withDefaults(defineProps<{
  startAt: string
  endAt: string
  /** 校正後的伺服器時間 */
  serverNow: () => number
  /** light 用在品牌色橫幅上：白底紅字；dark 是一般內容區的黑底白字 */
  tone?: 'dark' | 'light'
  size?: 'md' | 'lg'
}>(), { tone: 'dark', size: 'md' })

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

/** 每 250ms 取樣而非每秒，顯示的秒數才會準時翻動。 */
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
    // 超過一天就把天數拆出來，「8717:52:50」沒有人讀得懂
    days: Math.floor(total / 86400),
    hours: String(Math.floor((total % 86400) / 3600)).padStart(2, '0'),
    minutes: String(Math.floor((total % 3600) / 60)).padStart(2, '0'),
    seconds: String(total % 60).padStart(2, '0'),
  }
})

const label = computed(() => {
  if (phase.value === 'before') return '距開賣'
  if (phase.value === 'running') return '距結束'
  return '活動已結束'
})

const box = computed(() => [
  'figure grid place-items-center rounded-sm tabular',
  props.size === 'lg' ? 'h-11 min-w-[2.75rem] px-1.5 text-xl' : 'h-7 min-w-[1.9rem] px-1 text-sm',
  props.tone === 'light' ? 'bg-white text-accent' : 'bg-ink-inverse text-white',
].join(' '))

const colon = computed(() =>
  props.tone === 'light' ? 'text-white/80' : 'text-ink-muted')

defineExpose({ phase })
</script>

<template>
  <div class="flex items-center gap-2">
    <span
      class="text-xs font-semibold"
      :class="tone === 'light' ? 'text-white/90' : 'text-ink-muted'"
    >
      {{ label }}
    </span>
    <div v-if="phase !== 'ended'" class="flex items-center gap-1">
      <template v-if="parts.days > 0">
        <span :class="box">{{ parts.days }}</span>
        <span class="text-xs font-semibold" :class="colon">天</span>
      </template>
      <span :class="box">{{ parts.hours }}</span>
      <span class="font-bold" :class="colon">:</span>
      <span :class="box">{{ parts.minutes }}</span>
      <span class="font-bold" :class="colon">:</span>
      <span :class="box">{{ parts.seconds }}</span>
    </div>
  </div>
</template>
