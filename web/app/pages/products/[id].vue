<script setup lang="ts">
import { useAddresses } from '~/composables/useAddresses'
import { useCheckout } from '~/composables/useCheckout'
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
  if (state.value.kind === 'failed') {
    reset()
  }
})

useHead(() => ({ title: product.value?.name ?? '商品' }))
</script>

<template>
  <main v-if="product" class="mx-auto max-w-3xl px-5 py-10">
    <NuxtLink to="/products" class="text-sm text-[var(--ink-muted)] hover:underline">
      ← 全部商品
    </NuxtLink>

    <header class="mt-4">
      <h1 class="text-3xl font-black tracking-tight">{{ product.name }}</h1>
      <p v-if="product.brand" class="mt-1 text-sm text-[var(--ink-muted)]">
        {{ product.brand }}
      </p>
      <p v-if="product.description" class="mt-4 text-[var(--ink-muted)]">
        {{ product.description }}
      </p>
    </header>

    <section class="mt-8" aria-labelledby="spec-heading">
      <h2 id="spec-heading" class="text-sm font-semibold text-[var(--ink-muted)]">
        選擇規格
      </h2>
      <div class="mt-3 flex flex-wrap gap-2">
        <button
          v-for="sku in product.skus"
          :key="sku.skuId"
          type="button"
          :disabled="!sku.purchasable"
          :aria-pressed="sku.skuId === selectedSkuId"
          class="rounded border px-4 py-2 text-sm transition
                 disabled:cursor-not-allowed disabled:opacity-40"
          :class="sku.skuId === selectedSkuId
            ? 'border-[var(--accent)] text-[var(--accent)]'
            : 'border-[var(--line)] hover:border-[var(--accent)]'"
          @click="selectedSkuId = sku.skuId"
        >
          {{ sku.specDisplay }}
        </button>
      </div>
    </section>

    <!-- 價格掛在 SKU 上，因此必須跟著選擇變動，不能顯示商品層級的單一價格 -->
    <p class="mt-8 font-mono text-3xl font-bold">
      NT$ {{ (selectedSku?.price ?? product.lowestPrice).toLocaleString() }}
    </p>

    <section class="mt-6 flex items-center gap-3">
      <label for="quantity" class="text-sm text-[var(--ink-muted)]">數量</label>
      <input
        id="quantity"
        v-model.number="quantity"
        type="number"
        min="1"
        max="999"
        class="w-20 rounded border border-[var(--line)] bg-[var(--surface)] px-3 py-2
               text-center font-mono"
      >
    </section>

    <p v-if="!auth.isAuthenticated" class="mt-6 text-sm text-[var(--ink-muted)]">
      請先登入才能購買。
    </p>

    <section v-else class="mt-8" aria-labelledby="address-heading">
      <h2 id="address-heading" class="text-sm font-semibold text-[var(--ink-muted)]">
        寄送至
      </h2>

      <div v-if="addresses.length > 0" class="mt-3 flex flex-col gap-2">
        <label
          v-for="address in addresses"
          :key="address.addressId"
          class="flex cursor-pointer items-start gap-3 rounded border p-4 transition"
          :class="address.addressId === selectedAddressId
            ? 'border-[var(--accent)]'
            : 'border-[var(--line)] hover:border-[var(--accent)]'"
        >
          <input
            v-model="selectedAddressId" type="radio" name="address"
            :value="address.addressId" class="mt-1"
          >
          <span>
            <span class="font-medium">{{ address.recipientName }}</span>
            <span class="ml-2 font-mono text-sm text-[var(--ink-muted)]">
              {{ address.phone }}
            </span>
            <span class="mt-1 block text-sm text-[var(--ink-muted)]">
              {{ address.fullAddress }}
            </span>
          </span>
        </label>
        <NuxtLink to="/addresses" class="mt-1 text-sm text-[var(--accent)] hover:underline">
          管理地址 →
        </NuxtLink>
      </div>

      <p v-else class="mt-3 text-sm text-[var(--ink-muted)]">
        還沒有收貨地址，
        <NuxtLink to="/addresses" class="text-[var(--accent)] hover:underline">
          先新增一筆
        </NuxtLink>
        才能下單。
      </p>
    </section>

    <button
      type="button"
      :disabled="!canBuy"
      class="mt-6 w-full rounded bg-[var(--accent)] px-6 py-4 font-semibold text-white
             transition disabled:cursor-not-allowed disabled:opacity-40"
      @click="buy"
    >
      {{ submitting ? '處理中⋯' : '立即購買' }}
    </button>

    <p
      v-if="state.kind === 'failed'"
      class="mt-4 rounded border border-[var(--danger)] p-4 text-sm text-[var(--danger)]"
      role="alert"
    >
      {{ state.message }}
    </p>

    <AuthPanel class="mt-10" />
  </main>

  <main v-else class="mx-auto max-w-3xl px-5 py-10">
    <p class="text-[var(--ink-muted)]">找不到這個商品。</p>
  </main>
</template>
