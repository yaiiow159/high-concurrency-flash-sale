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
  <div>
    <PageHeader
      eyebrow="Cart"
      title="購物車"
      :description="!auth.isAuthenticated && items.length > 0
        ? '目前存在這台裝置上，登入後會自動併入你的帳號'
        : '價格為當下的目錄價；結帳時會重新計算並凍結進訂單'"
    />

    <p
      v-if="view && view.removedCount > 0"
      class="mb-6 rounded-sm border border-line bg-sunken px-4 py-3 text-sm text-ink-muted"
    >
      有 {{ view.removedCount }} 項商品已下架，已從購物車移除。
    </p>

    <p
      v-if="error"
      class="mb-6 rounded-sm border border-danger/40 bg-danger-soft px-4 py-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>

    <div v-if="items.length > 0" class="grid gap-8 lg:grid-cols-[1fr_20rem] lg:items-start">
      <ul class="flex flex-col gap-3">
        <li v-for="item in items" :key="item.skuId">
          <AppCard :muted="!item.purchasable" class="p-5">
            <div class="flex flex-wrap items-start justify-between gap-4">
              <div class="min-w-0">
                <NuxtLink
                  :to="`/products/${item.productId}`"
                  class="font-medium transition-colors hover:text-accent"
                >
                  {{ item.productName }}
                </NuxtLink>
                <p class="mt-1 text-sm text-ink-muted">{{ item.specDisplay }}</p>
                <p v-if="!item.purchasable" class="mt-1.5 text-sm text-danger">
                  已下架，無法結帳
                </p>
              </div>
              <div class="text-right">
                <MoneyText :amount="item.subtotal" size="lg" />
                <p class="mt-1 flex items-baseline justify-end gap-1.5 text-xs text-ink-faint">
                  <span>單價</span>
                  <MoneyText :amount="item.unitPrice" size="sm" tone="muted" />
                </p>
              </div>
            </div>

            <div class="mt-5 flex items-center gap-4 border-t border-line pt-4">
              <label class="eyebrow" :for="`qty-${item.skuId}`">數量</label>
              <input
                :id="`qty-${item.skuId}`"
                type="number" min="0" max="999" :value="item.quantity"
                class="figure w-20 rounded-sm border border-line bg-surface px-3 py-1.5 text-center text-sm"
                @change="updateQuantity(item.skuId, Number(($event.target as HTMLInputElement).value))"
              >
              <AppButton variant="danger" size="sm" class="ml-auto" @click="remove(item.skuId)">
                移除
              </AppButton>
            </div>
          </AppCard>
        </li>
      </ul>

      <!-- 摘要固定在側欄：長購物車不必捲回頂端才看得到金額 -->
      <AppCard class="p-5 lg:sticky lg:top-24">
        <h2 class="eyebrow mb-4">訂單摘要</h2>
        <dl class="flex flex-col gap-2.5 text-sm">
          <div class="flex justify-between">
            <dt class="text-ink-muted">商品數量</dt>
            <dd class="figure">{{ view?.totalQuantity ?? 0 }}</dd>
          </div>
          <div class="flex items-baseline justify-between border-t border-line pt-3">
            <dt class="font-medium">合計</dt>
            <dd><MoneyText :amount="view?.totalAmount" size="lg" /></dd>
          </div>
        </dl>
        <p v-if="hasUnpurchasable" class="mt-2 text-xs text-ink-faint">
          金額不含已下架的商品
        </p>

        <AppButton class="mt-5" size="lg" block @click="navigateTo('/checkout')">
          前往結帳
        </AppButton>
      </AppCard>
    </div>

    <EmptyState v-else title="購物車是空的。" hint="逛逛商品，把想要的加進來。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        去逛商品
      </AppButton>
    </EmptyState>
  </div>
</template>
