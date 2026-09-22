import { beforeEach, describe, expect, it } from 'vitest'
import {
  PENDING_MAX_AGE_MILLIS, clearPending, deliveryUnknown, pendingKey, readPending, writePending,
} from '~/utils/seckillPending'

/** 最小的 Storage 替身：只實作用得到的三個方法。 */
function memoryStore() {
  const data = new Map<string, string>()
  return {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value) },
    removeItem: (key: string) => { data.delete(key) },
    data,
  }
}

describe('未完成的搶購', () => {
  let store: ReturnType<typeof memoryStore>
  const NOW = 1_700_000_000_000

  beforeEach(() => {
    store = memoryStore()
  })

  it('寫入後讀得回來，requestId 原樣保留', () => {
    writePending(store, 7, { requestId: 'r-1', orderNo: null, startedAt: NOW })
    expect(readPending(store, 7, NOW + 1000)).toEqual({ requestId: 'r-1', orderNo: null, startedAt: NOW })
  })

  it('不同活動互不干擾', () => {
    writePending(store, 7, { requestId: 'r-1', orderNo: 'S1', startedAt: NOW })
    expect(readPending(store, 8, NOW)).toBeNull()
  })

  it('過期的紀錄不接回，並且順手清掉', () => {
    writePending(store, 7, { requestId: 'r-1', orderNo: 'S1', startedAt: NOW })
    expect(readPending(store, 7, NOW + PENDING_MAX_AGE_MILLIS + 1)).toBeNull()
    expect(store.data.has(pendingKey(7))).toBe(false)
  })

  it('壞掉的內容當成沒有，不拋例外', () => {
    store.setItem(pendingKey(7), '{not json')
    expect(readPending(store, 7, NOW)).toBeNull()
    store.setItem(pendingKey(7), JSON.stringify({ orderNo: 'S1' }))
    expect(readPending(store, 7, NOW)).toBeNull()
  })

  it('儲存空間不可用時安靜失敗', () => {
    const broken = {
      getItem: () => { throw new Error('denied') },
      setItem: () => { throw new Error('denied') },
      removeItem: () => { throw new Error('denied') },
    }
    expect(() => writePending(broken, 7, { requestId: 'r', orderNo: null, startedAt: NOW })).not.toThrow()
    expect(readPending(broken, 7, NOW)).toBeNull()
    expect(() => clearPending(broken, 7)).not.toThrow()
  })

  it('清除後讀不到', () => {
    writePending(store, 7, { requestId: 'r-1', orderNo: 'S1', startedAt: NOW })
    clearPending(store, 7)
    expect(readPending(store, 7, NOW)).toBeNull()
  })
})

describe('「不知道送到沒」的判斷', () => {
  it('網路錯誤與 5xx 要保留冪等鍵', () => {
    expect(deliveryUnknown('NETWORK', 0)).toBe(true)
    expect(deliveryUnknown('UNKNOWN', 0)).toBe(true)
    expect(deliveryUnknown('S0001', 503)).toBe(true)
  })

  it('明確的業務拒絕可以作廢', () => {
    expect(deliveryUnknown('B0001', 409)).toBe(false)
    expect(deliveryUnknown('B0054', 403)).toBe(false)
    expect(deliveryUnknown('S0002', 429)).toBe(false)
  })
})
