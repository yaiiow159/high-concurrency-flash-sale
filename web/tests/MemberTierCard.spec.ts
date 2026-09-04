import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MemberTierCard from '~/components/MemberTierCard.vue'
import SkeletonBlock from '~/components/SkeletonBlock.vue'
import type { MemberProfileView } from '~/types/api'

/**
 * 會員等級卡。
 *
 * 這是會員中心的第一屏，而它壞掉的方式都不會拋錯：
 * 進度條算出 150% 只是一條超出容器的長條、
 * 負餘額顯示成一個沒有說明的紅色數字只是「看起來像系統壞了」。
 */
function profile(overrides: Partial<MemberProfileView> = {}): MemberProfileView {
  return {
    userId: 1,
    tier: 'GOLD',
    tierName: '金卡會員',
    multiplier: 1.5,
    pointBalance: 1200,
    inDebt: false,
    cumulativeSpend: 60000,
    nextTier: 'PLATINUM',
    nextTierName: '白金會員',
    amountToNextTier: 140000,
    progressToNextTier: 6,
    ...overrides,
  }
}

function render(p: MemberProfileView | null, loading = false) {
  return mount(MemberTierCard, {
    props: { profile: p, loading },
    global: { components: { SkeletonBlock } },
  })
}

describe('MemberTierCard', () => {
  it('一眼要看得到三件事：等級、積分、離下一級還差多少', () => {
    const wrapper = render(profile())

    expect(wrapper.text()).toContain('金卡會員')
    expect(wrapper.text()).toContain('1,200')
    expect(wrapper.text()).toContain('白金會員')
    expect(wrapper.text()).toContain('140,000')
  })

  it('顯示回饋倍率——只給徽章的等級制度沒有作用', () => {
    expect(render(profile()).text()).toContain('1.5×')
  })

  it('最高等級不顯示「還差 0 元」，而是說已達最高', () => {
    const wrapper = render(profile({
      tier: 'PLATINUM', tierName: '白金會員',
      nextTier: null, nextTierName: null,
      amountToNextTier: 0, progressToNextTier: 100,
    }))

    expect(wrapper.text()).toContain('已達最高等級')
    expect(wrapper.text()).not.toContain('還差')
  })

  it('負餘額要附說明——只顯示紅色的 -50 會讓人以為系統壞了', () => {
    const wrapper = render(profile({ pointBalance: -50, inDebt: true }))

    expect(wrapper.text()).toContain('-50')
    expect(wrapper.text()).toContain('補足後才能再兌換')
  })

  it('進度條寬度直接用後端給的百分比，前端不自己算', () => {
    const wrapper = render(profile({ progressToNextTier: 42 }))

    const bar = wrapper.findAll('div').find(
      (d) => (d.attributes('style') ?? '').includes('width'))
    expect(bar?.attributes('style')).toContain('width: 42%')
  })

  it('各等級有不同的視覺——只靠文字分辨的話「等級」就沒有意義了', () => {
    const gold = render(profile({ tier: 'GOLD' })).attributes('style')
    const bronze = render(profile({ tier: 'BRONZE' })).attributes('style')

    expect(gold).not.toEqual(bronze)
  })

  it('未知的等級代號不會炸掉，退回預設樣式', () => {
    const wrapper = render(profile({ tier: 'DIAMOND' }))

    expect(wrapper.attributes('style')).toContain('linear-gradient')
  })

  it('載入中顯示骨架，不是一張空白卡', () => {
    expect(render(null, true).findComponent(SkeletonBlock).exists()).toBe(true)
  })
})
