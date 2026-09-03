import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ProductTile from '~/components/ProductTile.vue'

/**
 * 商品色塊。
 *
 * <p>它用商品 ID 推導一個顏色，取代還不存在的商品圖。
 * <b>唯一真正會壞的地方是「同一個商品每次要拿到同一個顏色」</b>——
 * 若改成隨機或依時間，商品列表會在每次重整時整片變色，
 * 那看起來像壞掉而不是像設計。
 */
function styleOf(seed: number | string) {
  return mount(ProductTile, { props: { seed } }).attributes('style') ?? ''
}

describe('商品色塊', () => {
  it('同一個 seed 永遠得到同一個顏色', () => {
    expect(styleOf(42)).toBe(styleOf(42))
    expect(styleOf('sku-abc')).toBe(styleOf('sku-abc'))
  })

  it('不同 seed 會分到不同顏色（否則整片會長一樣）', () => {
    const colours = new Set([1, 2, 3, 4, 5, 6].map((n) => styleOf(n)))

    // 色盤只有六色，不強求全不同，但至少要真的有分散
    expect(colours.size).toBeGreaterThan(1)
  })

  it('數字與字串形式的同一個 seed 視為同一個商品', () => {
    // 後端有時給 number、有時給 string（路由參數），同一個商品不該換色
    expect(styleOf(2001)).toBe(styleOf('2001'))
  })

  it('沒有名稱時不顯示文字標記，而不是印出 undefined', () => {
    expect(mount(ProductTile, { props: { seed: 1 } }).text()).toBe('')
  })

  it('中文取一個字，拉丁取兩個字', () => {
    expect(mount(ProductTile, { props: { seed: 1, label: '限量球鞋' } }).text()).toBe('限')
    expect(mount(ProductTile, { props: { seed: 1, label: 'iPhone' } }).text()).toBe('IP')
  })

  it('對裝飾性元素隱藏於輔助技術——它不帶任何資訊', () => {
    const wrapper = mount(ProductTile, { props: { seed: 1, label: '商品' } })

    expect(wrapper.attributes('aria-hidden')).toBe('true')
  })
})
