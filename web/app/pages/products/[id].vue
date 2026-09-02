<script setup lang="ts">
import { useAddresses } from '~/composables/useAddresses'
import { useCheckout } from '~/composables/useCheckout'
import { useCartStore } from '~/stores/cart'
import { useAuthStore } from '~/stores/auth'
import type { ApiResponse, ProductView, SkuView } from '~/types/api'

/**
 * 商品詳情與直接購買。
 *
 * 頁面本身走 ISR（商品資料變動慢），但下單一定是客戶端的動作——
 * 它帶身分、會改狀態，永遠不該出現在被快取的 HTML 裡。
 */
const route = useRoute()
const productId = route.params.id as string

const { data } = await useFetch<ApiResponse<ProductView>>(
  `/api/v1/catalog/products/${productId}`,
)
const product = computed(() => data.value?.data ?? null)

const auth = useAuthStore()
const { state, place, reset } = useCheckout()
const cart = useCartStore()

const addingToCart = ref(false)
const cartMessage = ref<string | null>(null)

/**
 * 加入購物車。未登入也能用——內容放在 localStorage，登入後自動併入。
 * 這讓「先逛再登入」成為可能，而不是逼使用者一進站就登入。
 */
async function addToCart() {
  if (!selectedSku.value) {
    return
  }
  addingToCart.value = true
  cartMessage.value = null
  try {
    await cart.addItem(selectedSku.value.skuId, quantity.value)
    cartMessage.value = '已加入購物車'
  } catch (cause) {
    cartMessage.value = (cause as { message?: string }).message ?? '加入購物車失敗'
  } finally {
    addingToCart.value = false
  }
}

/**
 * 地址在客戶端掛載後才取，絕不進 SSR——這一頁是 ISR 快取的，
 * 個資一旦進了快取的 HTML 就等於發給下一個訪客。
 */
const { addresses, defaultAddress, load: loadAddresses } = useAddresses()
const selectedAddressId = ref<number | null>(null)

onMounted(() => {
  if (auth.isAuthenticated) {
    loadAddresses()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    loadAddresses()
  }
})
// 預選預設地址，讓多數人不必多按一次
watchEffect(() => {
  if (selectedAddressId.value === null && defaultAddress.value) {
    selectedAddressId.value = defaultAddress.value.addressId
  }
})

/**
 * 預選第一個可購買的規格。
 *
 * 不預選「第一個」而是「第一個可買的」：把使用者放在一個
 * 按下去就會失敗的狀態上，是設計者偷懶而不是使用者的錯。
 */
const selectedSkuId = ref<number | null>(null)
watchEffect(() => {
  if (selectedSkuId.value === null && product.value) {
    selectedSkuId.value = product.value.skus.find((sku) => sku.purchasable)?.skuId ?? null
  }
})

const selectedSku = computed<SkuView | null>(
  () => product.value?.skus.find((sku) => sku.skuId === selectedSkuId.value) ?? null,
)

const quantity = ref(1)
const submitting = computed(() => state.value.kind === 'submitting')
const canBuy = computed(
  () => auth.isAuthenticated
    && selectedSku.value?.purchasable === true
    && selectedAddressId.value !== null
    && !submitting.value,
)

async function buy() {
  if (!selectedSku.value || selectedAddressId.value === null) {
    return
  }
  await place(
    [{ skuId: selectedSku.value.skuId, quantity: quantity.value }],
    selectedAddressId.value,
  )
  if (state.value.kind === 'placed') {
    await navigateTo(`/orders/${state.value.order.orderNo}`)
  }
}

// 換規格後先前的失敗訊息就不再適用，留著只會誤導
watch(selectedSkuId, () => {
  cartMessage.value = null
  if (state.value.kind === 'failed') {
    reset()
  }
})

useHead(() => ({ title: product.value?.name ?? '商品' }))
</script>

<template>
  <div v-if="product" class="grid gap-10 lg:grid-cols-[1.1fr_1fr] lg:items-start">
    <!-- 左欄：商品本身 -->
    <div>
      <NuxtLink to="/products" class="text-sm text-ink-muted transition-colors hover:text-ink">
        ← 全部商品
      </NuxtLink>

      <p v-if="product.brand" class="eyebrow mt-6">{{ product.brand }}</p>
      <h1 class="mt-1.5 text-3xl font-bold tracking-tight">{{ product.name }}</h1>
      <p v-if="product.description" class="mt-4 max-w-prose text-ink-muted">
        {{ product.description }}
      </p>

      <!-- 價格跟著規格走，不是商品層級的單一數字——這是 SPU/SKU 分離的重點 -->
      <div class="mt-8">
        <MoneyText :amount="selectedSku?.price ?? product.lowestPrice" size="xl" />
      </div>

      <section class="mt-8" aria-labelledby="spec-heading">
        <h2 id="spec-heading" class="eyebrow mb-3">選擇規格</h2>
        <div class="flex flex-wrap gap-2">
          <button
            v-for="sku in product.skus"
            :key="sku.skuId"
            type="button"
            :disabled="!sku.purchasable"
            :aria-pressed="sku.skuId === selectedSkuId"
            class="rounded-sm border px-4 py-2.5 text-sm transition-colors
                   disabled:cursor-not-allowed disabled:opacity-40"
            :class="sku.skuId === selectedSkuId
              ? 'border-accent bg-accent-soft text-accent'
              : 'border-line hover:border-line-strong'"
            @click="selectedSkuId = sku.skuId"
          >
            {{ sku.specDisplay }}
          </button>
        </div>
      </section>

      <section class="mt-6 flex items-center gap-3">
        <label for="quantity" class="eyebrow">數量</label>
        <input
          id="quantity"
          v-model.number="quantity"
          type="number"
          min="1"
          max="999"
          class="figure w-20 rounded-sm border border-line bg-surface px-3 py-2 text-center"
        >
      </section>
    </div>

    <!-- 右欄：購買動作 -->
    <AppCard class="p-6">
      <template v-if="auth.isAuthenticated">
        <section aria-labelledby="address-heading">
          <h2 id="address-heading" class="eyebrow mb-3">寄送至</h2>

          <div v-if="addresses.length > 0" class="flex flex-col gap-2">
            <label
              v-for="address in addresses"
              :key="address.addressId"
              class="flex cursor-pointer items-start gap-3 rounded-sm border p-3.5
                     text-sm transition-colors"
              :class="address.addressId === selectedAddressId
                ? 'border-accent bg-accent-soft'
                : 'border-line hover:border-line-strong'"
            >
              <input
                v-model="selectedAddressId" type="radio" name="address"
                :value="address.addressId" class="mt-1 accent-[var(--accent)]"
              >
              <span>
                <span class="font-medium">{{ address.recipientName }}</span>
                <span class="figure ml-2 text-xs text-ink-faint">{{ address.phone }}</span>
                <span class="mt-1 block text-ink-muted">{{ address.fullAddress }}</span>
              </span>
            </label>
            <NuxtLink to="/addresses" class="mt-1 text-sm text-accent hover:underline">
              管理地址 →
            </NuxtLink>
          </div>

          <p v-else class="text-sm text-ink-muted">
            還沒有收貨地址，
            <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
            才能直接購買。
          </p>
        </section>

        <div class="mt-6 flex flex-col gap-2.5">
          <AppButton
            variant="secondary" size="lg" block
            :disabled="!selectedSku?.purchasable || addingToCart"
            @click="addToCart"
          >
            {{ addingToCart ? '加入中⋯' : '加入購物車' }}
          </AppButton>
          <AppButton size="lg" block :disabled="!canBuy" @click="buy">
            {{ submitting ? '處理中⋯' : '立即購買' }}
          </AppButton>
        </div>
      </template>

      <template v-else>
        <p class="text-sm text-ink-muted">
          可以先加入購物車，登入後會自動併入你的帳號。
        </p>
        <AppButton
          class="mt-4" variant="secondary" size="lg" block
          :disabled="!selectedSku?.purchasable || addingToCart"
          @click="addToCart"
        >
          {{ addingToCart ? '加入中⋯' : '加入購物車' }}
        </AppButton>
        <AuthPanel class="mt-6" />
      </template>

      <p v-if="cartMessage" class="mt-3 text-sm text-ink-muted" role="status">
        {{ cartMessage }}
        <NuxtLink to="/cart" class="text-accent hover:underline">查看購物車 →</NuxtLink>
      </p>

      <p
        v-if="state.kind === 'failed'"
        class="mt-3 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
        role="alert"
      >
        {{ state.message }}
      </p>
    </AppCard>
  </div>

  <EmptyState v-else title="找不到這個商品。">
    <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
      回商品列表
    </AppButton>
  </EmptyState>
</template>
