import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 購物車 store。
 *
 * <h2>測的是「不可信輸入」而不是快樂路徑</h2>
 *
 * 未登入時購物車放在 localStorage——那是<b>使用者改得動的地方</b>。
 * 開個 devtools 就能把 quantity 改成 -5 或 999999，或塞進一段不是陣列的 JSON。
 * 這個 store 最重要的行為不是「加入商品」，而是<b>讀到垃圾時不要炸掉</b>，
 * 以及不要把垃圾當成合法數量送去後端。
 *
 * <p>另外測「無痕視窗」：那裡存取 localStorage 會直接拋例外，
 * 而那是正常狀態不是錯誤——購物車存不進去，頁面仍然要能用。
 */

const LOCAL_KEY = 'flash-sale.cart'

// store 依賴 useApi/useAuthStore，兩者都走 Nuxt 的自動匯入與執行期。
// 這裡只驗證本機購物車的邏輯，因此把它們換成最小替身。
vi.mock('~/composables/useApi', () => ({
  useApi: () => ({ request: vi.fn().mockResolvedValue(null) }),
}))
vi.mock('~/stores/auth', () => ({
  useAuthStore: () => ({ isAuthenticated: false }),
}))

const { useCartStore } = await import('~/stores/cart')

function seed(raw: string) {
  localStorage.setItem(LOCAL_KEY, raw)
}

/**
 * 種好資料後載入。
 *
 * store 建立時<b>不會</b>去讀 localStorage——要等 load()。
 * 那是刻意的：SSR 期間根本沒有 localStorage，在建立時就讀會直接炸掉。
 * 因此測試也走同一條路，而不是去戳內部狀態。
 */
async function loadWith(raw: string) {
  seed(raw)
  const cart = useCartStore()
  await cart.load()
  return cart
}

describe('購物車（未登入，內容在 localStorage）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
  })

  it('讀得回合法內容', async () => {
    const cart = await loadWith(JSON.stringify([{ skuId: 1, quantity: 2 }]))

    expect(cart.itemCount).toBe(2)
  })

  describe('內容不可信', () => {
    it.each([
      ['不是陣列', '{"skuId":1}'],
      ['不是 JSON', 'not json at all'],
      ['空字串', ''],
    ])('%s 時視為空車而不是炸掉', async (_label, raw) => {
      const cart = await loadWith(raw)

      expect(cart.itemCount).toBe(0)
    })

    it.each([
      ['數量為負', { skuId: 1, quantity: -5 }],
      ['數量為 0', { skuId: 1, quantity: 0 }],
      ['數量超過上限', { skuId: 1, quantity: 1_000_000 }],
      ['數量不是整數', { skuId: 1, quantity: 1.5 }],
      ['skuId 不是整數', { skuId: 'abc', quantity: 1 }],
      ['缺欄位', { skuId: 1 }],
    ])('%s 的那一筆被丟掉', async (_label, bad) => {
      const cart = await loadWith(JSON.stringify([bad, { skuId: 2, quantity: 3 }]))

      // 壞的丟掉、好的留著——整份丟掉會讓使用者莫名其妙少了東西
      expect(cart.itemCount).toBe(3)
    })

    it('品項種類數截到上限，避免有人塞爆前端', async () => {
      const cart = await loadWith(JSON.stringify(
        Array.from({ length: 200 }, (_, n) => ({ skuId: n + 1, quantity: 1 })),
      ))

      expect(cart.itemCount).toBe(50)
    })
  })

  describe('加入商品', () => {
    it('同一個 SKU 累加而不是新增一列', async () => {
      const cart = useCartStore()

      await cart.addItem(1, 2)
      await cart.addItem(1, 3)

      expect(cart.itemCount).toBe(5)
    })

    it('累加後仍夾在單品上限，不會送出一個後端一定會拒絕的數量', async () => {
      const cart = useCartStore()

      await cart.addItem(1, 900)
      await cart.addItem(1, 900)

      expect(cart.itemCount).toBe(999)
    })

    it('超過品項種類上限時明確報錯，而不是安靜地不加', async () => {
      const cart = useCartStore()
      for (let skuId = 1; skuId <= 50; skuId++) {
        await cart.addItem(skuId, 1)
      }

      await expect(cart.addItem(51, 1)).rejects.toThrow('50')
    })
  })

  describe('localStorage 不可用（無痕視窗）', () => {
    it('讀取拋例外時當成空車，頁面照樣能用', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('SecurityError')
      })
      const cart = useCartStore()

      await expect(cart.load()).resolves.toBeUndefined()
      expect(cart.itemCount).toBe(0)
      vi.restoreAllMocks()
    })

    it('寫入拋例外時不影響加入商品——只是換頁後會忘記', async () => {
      const cart = useCartStore()
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('QuotaExceededError')
      })

      await expect(cart.addItem(1, 2)).resolves.toBeUndefined()
      expect(cart.itemCount).toBe(2)
      vi.restoreAllMocks()
    })
  })
})
