<script setup lang="ts">
import { formatRemaining } from '~/utils/paymentMethods'

/**
 * 待付款訂單的倒數。起點是伺服器算好的「剩餘秒數」，不是拿期限減本機時間——
 * 期限只有十幾分鐘，客戶端時鐘差幾分鐘，倒數就是錯的。
 */
const props = defineProps<{
  remainingSeconds: number
  /** 期限的時間點，只用來顯示「請於 14:32 前付款」 */
  deadline: string | null
}>()

const emit = defineEmits<{ expired: [] }>()

/** 最後五分鐘換成警示色：那時才真的需要使用者注意。 */
const URGENT_BELOW_SECONDS = 300

const remaining = ref(props.remainingSeconds)
let endsAt = 0
let timer: ReturnType<typeof setInterval> | null = null
let expiredEmitted = false

function tick() {
  remaining.value = Math.max(0, Math.round((endsAt - performance.now()) / 1000))
  if (remaining.value === 0 && !expiredEmitted) {
    expiredEmitted = true
    stop()
    emit('expired')
  }
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function start(seconds: number) {
  stop()
  expiredEmitted = false
  // performance.now() 單調遞增，使用者中途改系統時間也不會讓倒數跳動
  endsAt = performance.now() + seconds * 1000
  tick()
  if (seconds > 0) {
    timer = setInterval(tick, 1000)
  }
}

onMounted(() => start(props.remainingSeconds))
// 訂單重新載入後伺服器會給一個新的剩餘秒數，以它為準
watch(() => props.remainingSeconds, start)
onUnmounted(stop)

const urgent = computed(() => remaining.value <= URGENT_BELOW_SECONDS)
const deadlineTime = computed(() => props.deadline
  ? new Date(props.deadline).toLocaleTimeString('zh-TW', { hour: '2-digit', minute: '2-digit' })
  : '')
</script>

<template>
  <div
    class="flex items-center gap-3 rounded-sm border px-3.5 py-3"
    :class="urgent ? 'border-danger/40 bg-danger-soft' : 'border-line bg-sunken'"
    role="timer"
    aria-live="off"
  >
    <svg
      viewBox="0 0 24 24" class="h-5 w-5 shrink-0" :class="urgent ? 'text-danger' : 'text-ink-muted'"
      fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"
    >
      <circle cx="12" cy="13" r="7.5" />
      <path d="M12 9v4l2.5 2M9.5 3h5" stroke-linecap="round" />
    </svg>
    <div class="min-w-0 flex-1">
      <template v-if="remaining > 0">
        <p class="text-xs" :class="urgent ? 'text-danger' : 'text-ink-muted'">
          付款剩餘時間<span v-if="deadlineTime">（{{ deadlineTime }} 前）</span>
        </p>
        <p class="figure text-xl font-bold leading-tight" :class="urgent ? 'text-danger' : 'text-ink'">
          {{ formatRemaining(remaining) }}
        </p>
      </template>
      <template v-else>
        <p class="text-sm font-semibold text-danger">付款期限已過</p>
        <p class="text-xs text-ink-muted">訂單將自動取消，保留的庫存會釋出。</p>
      </template>
    </div>
  </div>
</template>
