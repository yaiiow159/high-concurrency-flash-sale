import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MoneyText from '~/components/MoneyText.vue'
import PriceBreakdown from '~/components/PriceBreakdown.vue'
import type { OrderDiscount } from '~/types/api'

/**
 * 金額明細。
 *
 * 這個元件同時出現在結帳頁與訂單詳情頁，顯示的是**使用者要付多少錢**。
 * 它壞掉的方式不會拋錯，只會安靜地少顯示一行折扣，
 * 而使用者會在信用卡帳單上才發現金額跟畫面說的不一樣。
 */
function discount(name: string, amount: number, sourceId: number | null = 1): OrderDiscount {
  return { sourceType: 'ORDER_DISCOUNT', sourceId, name, amount }
}

function render(props: {
  subtotal?: number | null
  discounts?: OrderDiscount[]
  payable?: number | null
  shippingFee?: number | null
  shippingKnown?: boolean
  shippingZone?: string | null
}) {
  return mount(PriceBreakdown, {
    props: { subtotal: 1000, discounts: [], payable: 1000, ...props },
    global: { components: { MoneyText } },
  })
}

describe('PriceBreakdown', () => {
  it('沒有折扣時只顯示一個金額，不顯示「小計」與「應付」兩行', () => {
    // 沒折扣卻列出「小計 1000 / 應付 1000」，使用者會停下來找那兩行差在哪
    const wrapper = render({ discounts: [], payable: 1000 })

    expect(wrapper.text()).not.toContain('小計')
    expect(wrapper.text()).not.toContain('應付')
    expect(wrapper.text()).toContain('1,000')
  })

  it('折扣逐筆列出，不是加總成一行', () => {
    // 使用者問的是「為什麼折了 2500」，那需要看到是哪幾個優惠
    const wrapper = render({
      subtotal: 10000,
      discounts: [discount('滿千折百', 100), discount('新客券', 2400, 9)],
      payable: 7500,
    })

    expect(wrapper.text()).toContain('滿千折百')
    expect(wrapper.text()).toContain('新客券')
    expect(wrapper.text()).toContain('小計')
    expect(wrapper.text()).toContain('應付')
  })

  it('折扣金額前面有負號——沒有的話「折 100」與「加 100」長得一樣', () => {
    const wrapper = render({
      subtotal: 1000,
      discounts: [discount('滿千折百', 100)],
      payable: 900,
    })

    expect(wrapper.text()).toContain('−')
  })

  it('同名折扣不會互相蓋掉——key 必須夠獨特', () => {
    // 兩筆同名優惠若共用 key，Vue 只會渲染一筆，而金額就對不上了
    const wrapper = render({
      subtotal: 1000,
      discounts: [discount('折抵', 50, 1), discount('折抵', 30, 2)],
      payable: 920,
    })

    expect(wrapper.text()).toContain('50')
    expect(wrapper.text()).toContain('30')
  })

  it('金額還沒載入時不顯示 0——那會讓畫面閃一下「免費」', () => {
    const wrapper = render({ subtotal: null, discounts: [], payable: null })

    expect(wrapper.text()).not.toContain('0')
  })

  describe('運費', () => {
    it('運費未知時顯示「選擇地址後計算」，不是 NT$ 0', () => {
      // NT$ 0 會讓使用者以為免運，然後在下一步被多收錢
      const wrapper = render({
        subtotal: 1000, discounts: [], payable: 1000,
        shippingFee: 0, shippingKnown: false,
      })

      expect(wrapper.text()).toContain('選擇地址後計算')
      expect(wrapper.text()).not.toContain('免運')
    })

    it('運費為 0 且算得出來時顯示「免運」——那是使用者拿到的好處', () => {
      const wrapper = render({
        subtotal: 3000, discounts: [], payable: 3000,
        shippingFee: 0, shippingKnown: true,
      })

      expect(wrapper.text()).toContain('免運')
    })

    it('總計要含運費——只顯示商品金額會讓帳單對不起來', () => {
      const wrapper = render({
        subtotal: 1000, discounts: [], payable: 1000,
        shippingFee: 80, shippingKnown: true,
      })

      expect(wrapper.text()).toContain('1,080')
    })

    it('運費未知時總計不加運費，避免顯示一個之後會變的數字', () => {
      const wrapper = render({
        subtotal: 1000, discounts: [], payable: 1000,
        shippingFee: 0, shippingKnown: false,
      })

      expect(wrapper.text()).toContain('商品小計')
      expect(wrapper.text()).not.toContain('應付')
    })

    it('離島要說得出是哪一區——沒有解釋的高運費只會變成客服電話', () => {
      const wrapper = render({
        subtotal: 1000, discounts: [], payable: 1000,
        shippingFee: 200, shippingKnown: true, shippingZone: '離島',
      })

      expect(wrapper.text()).toContain('離島')
      expect(wrapper.text()).toContain('200')
    })

    it('沒有運費概念時（秒殺訂單）完全不顯示那一列', () => {
      const wrapper = render({ subtotal: 1000, discounts: [], payable: 1000 })

      expect(wrapper.text()).not.toContain('運費')
    })
  })
})
