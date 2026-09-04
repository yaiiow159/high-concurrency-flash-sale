import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StarRating from '~/components/StarRating.vue'

/**
 * 星等。
 *
 * 這個元件同時是顯示與輸入，而它壞掉的方式都不會拋錯：
 * 半星裁切算錯只是「4.3 分看起來像 4 分」，
 * 輸入模式退化成 div 只是「鍵盤填不了表單」——兩者都要有人專門去看才會發現。
 */
function fillWidth(wrapper: ReturnType<typeof mount>): string {
  const overlay = wrapper.find('.absolute')
  return (overlay.attributes('style') ?? '').replace(/\s/g, '')
}

describe('StarRating', () => {
  describe('顯示模式', () => {
    it('小數用寬度裁切，4.3 分不會被畫成 4 分或 5 分', () => {
      const wrapper = mount(StarRating, { props: { value: 4.3 } })

      // 4.3 / 5 = 86%
      expect(fillWidth(wrapper)).toContain('width:86%')
    })

    it('滿分是 100%', () => {
      expect(fillWidth(mount(StarRating, { props: { value: 5 } }))).toContain('width:100%')
    })

    it('0 分是 0%，而不是負值或 NaN', () => {
      expect(fillWidth(mount(StarRating, { props: { value: 0 } }))).toContain('width:0%')
    })

    it('超出範圍的分數被夾住——後端若回了 7，畫面不該溢出五顆星', () => {
      expect(fillWidth(mount(StarRating, { props: { value: 7 } }))).toContain('width:100%')
      expect(fillWidth(mount(StarRating, { props: { value: -1 } }))).toContain('width:0%')
    })

    it('顯示模式帶得出可讀的無障礙標籤', () => {
      const wrapper = mount(StarRating, { props: { value: 4.3 } })

      expect(wrapper.find('[role="img"]').attributes('aria-label')).toContain('4.3')
    })

    it('單一根節點——多根會讓父層傳的 class 在 SSR 掉光，造成 hydration mismatch', () => {
      const wrapper = mount(StarRating, {
        props: { value: 3 },
        attrs: { class: 'justify-center opacity-40' },
      })

      // class 必須落在根節點上。這一條釘住的是一個實際發生過的缺陷：
      // v-if/v-else 兩個並列的根讓 Vue 把元件當成 fragment，
      // 伺服器渲染不帶那些 class，客戶端帶——首屏樣式因此是錯的
      expect(wrapper.classes()).toContain('justify-center')
      expect(wrapper.classes()).toContain('opacity-40')
    })

    it('顯示模式不產生任何表單控制項——它不該被 Tab 停留', () => {
      const wrapper = mount(StarRating, { props: { value: 3 } })

      expect(wrapper.findAll('input')).toHaveLength(0)
    })
  })

  describe('輸入模式', () => {
    it('用原生 radio，鍵盤與螢幕閱讀器才拿得到它', () => {
      const wrapper = mount(StarRating, { props: { interactive: true } })

      const radios = wrapper.findAll('input[type="radio"]')
      expect(radios).toHaveLength(5)
    })

    it('選取會更新 v-model', async () => {
      const wrapper = mount(StarRating, { props: { interactive: true, modelValue: 0 } })

      await wrapper.findAll('input[type="radio"]')[3]!.setValue()

      expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([4])
    })

    it('未選時提示「請選擇評分」，而不是顯示某個星等的形容詞', () => {
      const wrapper = mount(StarRating, { props: { interactive: true, modelValue: 0 } })

      expect(wrapper.text()).toContain('請選擇評分')
    })

    it('已選時同時給文字——只有星星時使用者得自己數', () => {
      const wrapper = mount(StarRating, { props: { interactive: true, modelValue: 5 } })

      expect(wrapper.text()).toContain('很好')
    })

    it('name 可指定，同一頁多組星等才不會互相搶選取', () => {
      const wrapper = mount(StarRating, {
        props: { interactive: true, name: 'stars-42' },
      })

      expect(wrapper.find('input').attributes('name')).toBe('stars-42')
    })
  })
})
