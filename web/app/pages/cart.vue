<script setup lang="ts">
import { useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'
import type { CartItemView, CartView } from '~/types/api'

/**
 * 購物車。
 *
 * **不做 SSR、不做 ISR**——購物車是每個人專屬的資料，
 * 進了被快取的 HTML 就等於發給下一個訪客。
 *
 * 未登入時內容在 localStorage，但那裡只有 skuId 與數量。
 * 商品名與價格得跟伺服器要——價格永遠由伺服器決定，
 * 存在瀏覽器裡的既會過期，也是使用者改得動的。
 */
const auth = useAuthStore()
const cart = useCartStore()
const { request } = useApi()

/** 未登入時，用本地的 skuId 去換取商品資料與價格。 */
const anonymousView = ref<CartView | null>(null)
const error = ref<string | null>(null)

const view = computed(() => (auth.isAuthenticated ? cart.remote : anonymousView.value))
const items = computed(() => view.value?.items ?? [])
const hasUnpurchasable = computed(() => items.value.some((item) => !item.purchasable))

async function refresh() {
  error.value = null
  try {
    await cart.load()
    if (!auth.isAuthenticated) {
      anonymousView.value = await priceLocalCart()
    }
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '無法載入購物車'
  }
}

/**
 * 未登入時的價格查詢。
 *
 * 用目錄的**批次**端點（匿名開放）一次取回所有品項的商品名與價格。
 * 逐筆查在 50 個品項的購物車上就是 50 次往返，而這是使用者反覆重整的頁面。
 *
 * 查不到的 SKU 不會出現在結果裡——放了幾天的本地購物車有商品被刪除是正常的。
 */
async function priceLocalCart(): Promise<CartView> {
  if (cart.local.length === 0) {
    return { items: [], totalAmount: 0, totalQuantity: 0, removedCount: 0 }
  }

  const ids = cart.local.map((item) => item.skuId)
  const lookups = await request<Array<{
    skuId: number
    productId: number
    productName: string
    specDisplay: string
    price: number
    purchasable: boolean
  }>>(`/api/v1/catalog/skus?ids=${ids.join(',')}`)

  const priced: CartItemView[] = []
  let total = 0
  for (const local of cart.local) {
    const found = lookups.find((lookup) => lookup.skuId === local.skuId)
    if (!found) {
      continue
    }
    const subtotal = found.price * local.quantity
    priced.push({
      skuId: found.skuId,
      productId: found.productId,
      productName: found.productName,
      specDisplay: found.specDisplay,
      unitPrice: found.price,
      quantity: local.quantity,
      subtotal,
      purchasable: found.purchasable,
    })
    if (found.purchasable) {
      total += subtotal
    }
  }

  return {
    items: priced,
    totalAmount: total,
    totalQuantity: priced.reduce((sum, item) => sum + item.quantity, 0),
    // 本地購物車查不到的品項在下次寫入時自然消失，不需要特別回報
    removedCount: 0,
  }
}

async function updateQuantity(skuId: number, quantity: number) {
  error.value = null
  try {
    await cart.changeQuantity(skuId, quantity)
    await refresh()
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '調整失敗'
  }
}

async function remove(skuId: number) {
  error.value = null
  try {
    await cart.removeItem(skuId)
    await refresh()
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '移除失敗'
  }
}

onMounted(refresh)
watch(() => auth.isAuthenticated, refresh)

useHead({ title: '購物車' })
</script>

<template>
  <main class="mx-auto max-w-3xl px-5 py-10">
    <header class="flex flex-wrap items-baseline justify-between gap-3">
      <h1 class="text-3xl font-black tracking-tight">購物車</h1>
      <NuxtLink to="/products" class="text-sm text-[var(--accent)] hover:underline">
        繼續購物 →
      </NuxtLink>
    </header>

    <p v-if="!auth.isAuthenticated && items.length > 0" class="mt-2 text-sm text-[var(--ink-muted)]">
      目前存在這台裝置上，登入後會自動併入你的帳號。
    </p>

    <p v-if="view && view.removedCount > 0" class="mt-4 rounded border border-[var(--line)] p-4 text-sm">
      有 {{ view.removedCount }} 項商品已下架，已從購物車移除。
    </p>

    <p v-if="error" class="mt-4 text-sm text-[var(--danger)]" role="alert">{{ error }}</p>

    <ul v-if="items.length > 0" class="mt-6 flex flex-col gap-3">
      <li
        v-for="item in items"
        :key="item.skuId"
        class="rounded border border-[var(--line)] bg-[var(--surface)] p-5"
        :class="item.purchasable ? '' : 'opacity-60'"
      >
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div>
            <NuxtLink :to="`/products/${item.productId}`" class="font-semibold hover:underline">
              {{ item.productName }}
            </NuxtLink>
            <div class="mt-1 text-sm text-[var(--ink-muted)]">{{ item.specDisplay }}</div>
            <div v-if="!item.purchasable" class="mt-1 text-sm text-[var(--danger)]">
              已下架，無法結帳
            </div>
          </div>
          <div class="text-right">
            <div class="font-mono">NT$ {{ item.unitPrice.toLocaleString() }}</div>
            <div class="tabular mt-1 font-mono font-semibold">
              NT$ {{ item.subtotal.toLocaleString() }}
            </div>
          </div>
        </div>

        <div class="mt-4 flex items-center gap-3">
          <label class="text-sm text-[var(--ink-muted)]" :for="`qty-${item.skuId}`">數量</label>
          <input
            :id="`qty-${item.skuId}`"
            type="number" min="0" max="999" :value="item.quantity"
            class="w-20 rounded border border-[var(--line)] bg-[var(--surface)] px-3 py-1.5
                   text-center font-mono"
            @change="updateQuantity(item.skuId, Number(($event.target as HTMLInputElement).value))"
          >
          <button
            type="button" class="text-sm text-[var(--danger)] hover:underline"
            @click="remove(item.skuId)"
          >
            移除
          </button>
        </div>
      </li>
    </ul>

    <p v-else class="mt-10 text-[var(--ink-muted)]">購物車是空的。</p>

    <template v-if="items.length > 0">
      <p class="mt-6 text-right font-mono text-2xl font-bold">
        NT$ {{ (view?.totalAmount ?? 0).toLocaleString() }}
      </p>
      <p v-if="hasUnpurchasable" class="mt-1 text-right text-sm text-[var(--ink-muted)]">
        金額不含已下架的商品
      </p>

      <NuxtLink
        to="/checkout"
        class="mt-6 block rounded bg-[var(--accent)] px-6 py-4 text-center font-semibold
               text-white transition"
      >
        前往結帳
      </NuxtLink>
    </template>
  </main>
</template>
