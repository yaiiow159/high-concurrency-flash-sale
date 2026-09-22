import { describe, expect, it } from 'vitest'
import { describeQueue } from '~/utils/describeQueue'

describe('排隊提示', () => {
  it('沒有排隊資訊時不顯示', () => {
    expect(describeQueue(null)).toBeNull()
    expect(describeQueue(undefined)).toBeNull()
  })

  it('算不出等待時間時說「待估」，不說 0 秒', () => {
    expect(describeQueue({ ahead: 1200, estimatedWaitSeconds: -1 })).toBe('前面約 1,200 筆，時間待估')
  })

  it('一分鐘內用秒', () => {
    expect(describeQueue({ ahead: 30, estimatedWaitSeconds: 45 })).toBe('前面約 30 筆，約 45 秒')
  })

  it('超過一分鐘用分鐘，無條件進位', () => {
    expect(describeQueue({ ahead: 5000, estimatedWaitSeconds: 61 })).toBe('前面約 5,000 筆，約 2 分鐘')
  })
})
