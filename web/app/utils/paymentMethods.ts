import type { PaymentMethod } from '~/types/api'

export interface PaymentMethodOption {
  value: PaymentMethod
  label: string
  hint: string
}

/** 順序就是畫面上的順序；第一個是預設，與後端的 `PaymentMethod.DEFAULT` 一致。 */
export const PAYMENT_METHODS: readonly PaymentMethodOption[] = [
  { value: 'CREDIT_CARD', label: '信用卡', hint: 'Visa／Master／JCB，一次付清' },
  { value: 'LINE_PAY', label: 'LINE Pay', hint: '開啟 LINE 完成付款' },
  { value: 'ATM_TRANSFER', label: 'ATM 轉帳', hint: '取得虛擬帳號後轉入' },
] as const

/** 認不得的值原樣顯示，而不是假裝成某一種——後端新增方式而前端還沒跟上時，至少看得出是什麼。 */
export function paymentMethodLabel(value: string | null | undefined): string {
  if (!value) {
    return ''
  }
  return PAYMENT_METHODS.find((option) => option.value === value)?.label ?? value
}

/** 把剩餘秒數排成 mm:ss；超過一小時才帶小時。 */
export function formatRemaining(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const pad = (value: number) => String(value).padStart(2, '0')
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return hours > 0
    ? `${hours}:${pad(minutes)}:${pad(seconds % 60)}`
    : `${pad(minutes)}:${pad(seconds % 60)}`
}
