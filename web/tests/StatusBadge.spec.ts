import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusBadge from '~/components/StatusBadge.vue'

/**
 * 狀態標籤。
 *
 * <h2>為什麼值得測</h2>
 *
 * 這個元件是<b>後端列舉與畫面文字之間的對照表</b>，而後端這半年新增了
 * 六個狀態（訂單的 REFUNDED、退貨單的五個）。對照表漏掉一個不會報錯，
 * 只會把原始的英文列舉名直接印在畫面上——那種畫面看起來「還能用」，
 * 所以通常是使用者先看到，不是我們。
 *
 * <p>因此這裡逐一列出後端真的會送過來的每一個值。
 * 新增狀態卻忘了補文案時，這裡就會失敗。
 */

/** 後端會送過來的所有狀態。新增時兩邊要一起改。 */
const ORDER_STATUSES = [
  'PENDING_PAYMENT', 'PAID', 'SHIPPED', 'COMPLETED',
  'CANCELLED', 'FAILED', 'REFUNDED',
]
const SHIPMENT_STATUSES = ['READY', 'IN_TRANSIT', 'DELIVERED']
const RETURN_STATUSES = ['REQUESTED', 'APPROVED', 'RECEIVED', 'REFUNDED', 'REJECTED', 'CANCELLED']

function labelOf(status: string) {
  return mount(StatusBadge, { props: { status } }).text()
}

describe('狀態標籤', () => {
  it.each([...ORDER_STATUSES, ...SHIPMENT_STATUSES, ...RETURN_STATUSES])(
    '%s 有對應的中文文案，不會把列舉名直接印出來',
    (status) => {
      const label = labelOf(status)

      expect(label).not.toBe(status)
      expect(label).not.toMatch(/^[A-Z_]+$/)
    },
  )

  it('沒對照到的值退回顯示原字串，而不是空白或壞掉', () => {
    // 後端加了新狀態而前端還沒跟上時，至少要看得出「是哪個值」
    expect(labelOf('SOME_NEW_STATUS')).toBe('SOME_NEW_STATUS')
  })

  it('已取消與已駁回用警示色，已完成用成功色', () => {
    const danger = mount(StatusBadge, { props: { status: 'CANCELLED' } })
    const ok = mount(StatusBadge, { props: { status: 'COMPLETED' } })

    expect(danger.classes().join(' ')).toContain('danger')
    expect(ok.classes().join(' ')).toContain('ok')
  })

  it('已退款是中性色而不是綠色——它與「已完成」是完全不同的結局', () => {
    // 兩者都塗綠的話，訂單列表上一眼分不出哪些是正常完成、哪些是退掉的
    const refunded = mount(StatusBadge, { props: { status: 'REFUNDED' } })

    expect(refunded.classes().join(' ')).not.toContain('ok')
  })
})
