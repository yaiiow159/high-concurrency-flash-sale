import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ReturnTimeline from '~/components/ReturnTimeline.vue'
import type { ReturnRequestView } from '~/types/api'

/**
 * 退貨進度條。
 *
 * <h2>這支測試存在的理由</h2>
 *
 * 這個元件出過一個實機才發現的 bug：一張還在「待審核」的退貨單，
 * 進度條三個階段<b>全部亮著</b>。
 *
 * 原因是後端序列化時省略 null 欄位，前端收到的是 `undefined` 而不是 `null`，
 * 而當時的判斷寫的是 `reviewedAt !== null`——對 `undefined` 回 true。
 *
 * 那個 bug 型別檢查抓不到（型別當時宣告成 `string | null`，是型別在說謊），
 * 測試也抓不到（當時沒有測試），只有人眼看著畫面才會發現。
 * 所以這裡每一條都<b>刻意用「欄位不存在」而不是「欄位為 null」</b>來建資料，
 * 那才是後端真正送過來的形狀。
 */

/** 後端會省略 null 欄位，因此這裡只放真的會出現在 JSON 裡的鍵。 */
function request(overrides: Partial<ReturnRequestView> = {}): ReturnRequestView {
  return {
    returnNo: 'RMA-1',
    orderNo: 'ORD-1',
    status: 'REQUESTED',
    reason: 'CHANGED_MIND',
    requiresGoodsReturn: false,
    refundAmount: 100,
    lines: [],
    createdAt: '2026-09-04T10:00:00Z',
    ...overrides,
  } as ReturnRequestView
}

/**
 * 讀出每一步的標題與「是否已完成」。
 *
 * 標題直接取那個元素，而不是拿 `text()` 去切換行——
 * `text()` 會把整個節點的文字正規化成一串，切出來的會是「標題+時間+說明」全部。
 * 完成與否的判準與元件一致：圓點填色代表完成。
 */
function steps(wrapper: ReturnType<typeof mount>) {
  return wrapper.findAll('ol li').map((li) => ({
    label: li.find('p.font-medium').text(),
    done: li.find('span.rounded-full').classes().includes('bg-cta'),
  }))
}

describe('退貨進度條', () => {
  it('待審核時只有第一步完成——欄位缺席不等於已發生', () => {
    // reviewedAt / refundedAt 完全不存在，這正是後端送來的形狀
    const wrapper = mount(ReturnTimeline, { props: { request: request() } })

    const done = steps(wrapper).map((s) => s.done)
    expect(done).toEqual([true, false, false])
  })

  it('核准後第二步才完成', () => {
    const wrapper = mount(ReturnTimeline, {
      props: { request: request({ status: 'APPROVED', reviewedAt: '2026-09-04T11:00:00Z' }) },
    })

    expect(steps(wrapper).map((s) => s.done)).toEqual([true, true, false])
  })

  it('免寄回時不畫「寄回商品」那一步——畫一個永遠不會亮的步驟會讓人一直在等', () => {
    const wrapper = mount(ReturnTimeline, {
      props: { request: request({ requiresGoodsReturn: false }) },
    })

    const labels = steps(wrapper).map((s) => s.label)
    expect(labels).toHaveLength(3)
    expect(labels).not.toContain('已收到退回商品')
  })

  it('需寄回時多一個驗收步驟', () => {
    const wrapper = mount(ReturnTimeline, {
      props: { request: request({ requiresGoodsReturn: true }) },
    })

    expect(steps(wrapper).map((s) => s.label)).toContain('已收到退回商品')
  })

  it('已收貨但還沒退款時，只有最後一步未完成', () => {
    const wrapper = mount(ReturnTimeline, {
      props: {
        request: request({
          status: 'RECEIVED',
          requiresGoodsReturn: true,
          reviewedAt: '2026-09-04T11:00:00Z',
          receivedAt: '2026-09-05T09:00:00Z',
        }),
      },
    })

    expect(steps(wrapper).map((s) => s.done)).toEqual([true, true, true, false])
  })

  it('駁回與撤回不畫進度條——它們不是停在某一步，是走完了但結局不同', () => {
    for (const status of ['REJECTED', 'CANCELLED']) {
      const wrapper = mount(ReturnTimeline, {
        props: { request: request({ status, reviewNote: '不符合退貨條件' }) },
      })

      expect(wrapper.find('ol').exists()).toBe(false)
      expect(wrapper.text()).toContain('流程結束')
    }
  })

  it('駁回時把客服的理由顯示出來——只說「被駁回」會直接變成客訴', () => {
    const wrapper = mount(ReturnTimeline, {
      props: { request: request({ status: 'REJECTED', reviewNote: '商品已使用' }) },
    })

    expect(wrapper.text()).toContain('商品已使用')
  })
})
