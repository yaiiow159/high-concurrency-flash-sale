export type ToastTone = 'success' | 'error' | 'info'

export interface ToastAction {
  label: string
  to: string
}

export interface Toast {
  id: number
  tone: ToastTone
  message: string
  action?: ToastAction
}

/** 同時最多幾則。再多就是洗版，舊的先走。 */
const MAX_VISIBLE = 3
/** 錯誤留久一點：使用者需要時間讀完並決定怎麼辦。 */
const DURATION_MILLIS: Record<ToastTone, number> = { success: 3200, info: 3200, error: 6000 }

// 模組層級的狀態：只在瀏覽器被寫入（push 有守衛），SSR 的請求之間不會互相看到
const toasts = ref<Toast[]>([])
const timers = new Map<number, ReturnType<typeof setTimeout>>()
let nextId = 1

/**
 * 全站的操作回饋。住在 app.vue 而不是各頁自己畫——
 * 「加入後立刻換頁」的訊息寫在頁面裡，換頁的那一刻就跟著消失了。
 */
export function useToast() {
  function push(tone: ToastTone, message: string, action?: ToastAction): number {
    if (import.meta.server) {
      return 0
    }
    const id = nextId++
    const next = [...toasts.value, { id, tone, message, action }]
    next.slice(0, -MAX_VISIBLE).forEach((dropped) => hold(dropped.id))
    toasts.value = next.slice(-MAX_VISIBLE)
    schedule(id, DURATION_MILLIS[tone])
    return id
  }

  function schedule(id: number, millis: number): void {
    hold(id)
    timers.set(id, setTimeout(() => dismiss(id), millis))
  }

  /** 滑鼠停在上面時不該消失——他正在讀，或正要去按那個連結。 */
  function hold(id: number): void {
    const timer = timers.get(id)
    if (timer) {
      clearTimeout(timer)
      timers.delete(id)
    }
  }

  function release(id: number): void {
    const toast = toasts.value.find((item) => item.id === id)
    if (toast) {
      schedule(id, DURATION_MILLIS[toast.tone])
    }
  }

  function dismiss(id: number): void {
    hold(id)
    toasts.value = toasts.value.filter((item) => item.id !== id)
  }

  return {
    toasts: readonly(toasts),
    success: (message: string, action?: ToastAction) => push('success', message, action),
    error: (message: string, action?: ToastAction) => push('error', message, action),
    info: (message: string, action?: ToastAction) => push('info', message, action),
    hold,
    release,
    dismiss,
  }
}
