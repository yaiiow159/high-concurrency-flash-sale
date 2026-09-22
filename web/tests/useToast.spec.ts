import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useToast } from '~/composables/useToast'

describe('useToast', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    const { toasts, dismiss } = useToast()
    toasts.value.forEach((toast) => dismiss(toast.id))
    vi.useRealTimers()
  })

  it('成功訊息會自己消失', () => {
    const toast = useToast()
    toast.success('已加入購物車')
    expect(toast.toasts.value).toHaveLength(1)
    vi.advanceTimersByTime(3300)
    expect(toast.toasts.value).toHaveLength(0)
  })

  it('錯誤訊息留得比成功訊息久', () => {
    const toast = useToast()
    toast.error('加入失敗')
    vi.advanceTimersByTime(3300)
    expect(toast.toasts.value).toHaveLength(1)
    vi.advanceTimersByTime(3000)
    expect(toast.toasts.value).toHaveLength(0)
  })

  it('滑鼠停在上面時不消失，移開後重新計時', () => {
    const toast = useToast()
    const id = toast.success('已領取')
    toast.hold(id)
    vi.advanceTimersByTime(10_000)
    expect(toast.toasts.value).toHaveLength(1)
    toast.release(id)
    vi.advanceTimersByTime(3300)
    expect(toast.toasts.value).toHaveLength(0)
  })

  it('最多同時三則，舊的先走', () => {
    const toast = useToast()
    ;['一', '二', '三', '四'].forEach((message) => toast.info(message))
    expect(toast.toasts.value.map((item) => item.message)).toEqual(['二', '三', '四'])
  })

  it('狀態是全站共用的：換頁後另一個元件仍看得到', () => {
    useToast().success('已全部加入購物車', { label: '去結帳', to: '/cart' })
    expect(useToast().toasts.value[0]?.action?.to).toBe('/cart')
  })
})
