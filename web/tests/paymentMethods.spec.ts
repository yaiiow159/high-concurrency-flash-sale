import { describe, expect, it } from 'vitest'
import { PAYMENT_METHODS, formatRemaining, paymentMethodLabel } from '~/utils/paymentMethods'

describe('付款方式', () => {
  it('第一個是預設，與後端的 PaymentMethod.DEFAULT 一致', () => {
    expect(PAYMENT_METHODS[0]?.value).toBe('CREDIT_CARD')
  })

  it('認得的值顯示中文名稱', () => {
    expect(paymentMethodLabel('LINE_PAY')).toBe('LINE Pay')
    expect(paymentMethodLabel('ATM_TRANSFER')).toBe('ATM 轉帳')
  })

  it('認不得的值原樣顯示——後端先加了新方式時，至少看得出是什麼', () => {
    expect(paymentMethodLabel('APPLE_PAY')).toBe('APPLE_PAY')
    expect(paymentMethodLabel('')).toBe('')
    expect(paymentMethodLabel(null)).toBe('')
  })
})

describe('剩餘時間', () => {
  it('一小時內用 mm:ss', () => {
    expect(formatRemaining(899)).toBe('14:59')
    expect(formatRemaining(9)).toBe('00:09')
  })

  it('超過一小時才帶小時', () => {
    expect(formatRemaining(3725)).toBe('1:02:05')
  })

  it('負數與小數不會顯示成奇怪的東西', () => {
    expect(formatRemaining(-5)).toBe('00:00')
    expect(formatRemaining(59.9)).toBe('00:59')
  })
})
