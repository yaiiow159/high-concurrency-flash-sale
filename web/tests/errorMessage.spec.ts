import { describe, expect, it, vi } from 'vitest'
import { ApiError, errorMessage } from '~/composables/useApi'

/**
 * 錯誤訊息取值。
 *
 * 這個函式在 36 個地方被呼叫，而它壞掉的方式是**畫面顯示一句
 * 沒有幫助的話，同時真正的原因消失**——沒有例外、沒有紅字。
 * 因此這裡測的重點是「拿不到訊息時有沒有把原因記下來」。
 */
describe('errorMessage', () => {
  it('後端業務錯誤直接顯示它的訊息', () => {
    const cause = new ApiError('B0014', '訂單目前為 PAID，無法付款', 409, false)

    expect(errorMessage(cause, '付款失敗')).toBe('訂單目前為 PAID，無法付款')
  })

  it('業務錯誤的訊息是空字串時退回 fallback', () => {
    // 後端理論上不會回空訊息，但空字串會讓畫面出現一片空白，
    // 那比顯示一句籠統的話更糟
    const cause = new ApiError('B0014', '', 409, false)

    expect(errorMessage(cause, '付款失敗')).toBe('付款失敗')
  })

  it('技術性例外不把內部訊息秀給使用者，但要記進 console', () => {
    // TypeError 的訊息是給工程師看的（"Failed to fetch"、"x is not a function"），
    // 直接顯示只會讓使用者困惑，而吞掉又會讓問題無從查起
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const cause = new TypeError('Failed to fetch')

    expect(errorMessage(cause, '無法載入購物車')).toBe('無法載入購物車')
    expect(spy).toHaveBeenCalledWith('無法載入購物車', cause)
    spy.mockRestore()
  })

  it('丟出來的不是 Error 也不會炸，而且原因仍然被記下來', () => {
    // 先前那個 `(cause as { message?: string }).message` 在這裡會得到
    // undefined 然後安靜落到 fallback——原因就此消失
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})

    expect(errorMessage('something went wrong', '操作失敗')).toBe('操作失敗')
    expect(errorMessage(undefined, '操作失敗')).toBe('操作失敗')
    expect(errorMessage({ weird: true }, '操作失敗')).toBe('操作失敗')
    expect(spy).toHaveBeenCalledTimes(3)
    spy.mockRestore()
  })
})
