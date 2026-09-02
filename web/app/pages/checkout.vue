<script setup lang="ts">
import { useAddresses } from '~/composables/useAddresses'
import { useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'
import type { OrderView } from '~/types/api'

/**
 * 結帳頁。
 *
 * **不快取**——購物車與地址都是個資。
 *
 * 品項刻意**不由前端送出**：結帳請求只帶 requestId 與 addressId，
 * 買什麼由伺服器從購物車讀。讓前端送品項等於讓它決定要買什麼，
 * 那樣購物車就只是一個裝飾。
 */
const auth = useAuthStore()
const cart = useCartStore()
const { request } = useApi()
const { addresses, defaultAddress, load: loadAddresses } = useAddresses()

const selectedAddressId = ref<number | null>(null)
const submitting = ref(false)
const error = ref<string | null>(null)

/**
 * 這一次結帳的冪等鍵。
 *
 * 在進入頁面時就產生並保留，只有成功後才作廢——
 * 失敗重試必須沿用同一個值，因為「失敗」有可能只是回應在路上掉了、
 * 訂單其實已經建立。每次重試都換新值的話，使用者按兩次就會買到兩份。
 */
let requestId: string | null = null

const items = computed(() => cart.remote?.items ?? [])
const canSubmit = computed(
  () => items.value.length > 0
    && selectedAddressId.value !== null
    && !items.value.some((item) => !item.purchasable)
    && !submitting.value,
)

async function submit() {
  if (!canSubmit.value || selectedAddressId.value === null) {
    return
  }
  requestId ??= crypto.randomUUID()
  submitting.value = true
  error.value = null

  try {
    const order = await request<OrderView>('/api/v1/orders/checkout', {
      method: 'POST',
      authenticated: true,
      body: { requestId, addressId: selectedAddressId.value },
    })
    requestId = null
    cart.reset()
    await navigateTo(`/orders/${order.orderNo}`)
  } catch (cause) {
    error.value = (cause as { message?: string }).message ?? '結帳失敗，請稍後再試'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  if (!auth.isAuthenticated) {
    return
  }
  await Promise.all([cart.load(), loadAddresses()])
})
watchEffect(() => {
  if (selectedAddressId.value === null && defaultAddress.value) {
    selectedAddressId.value = defaultAddress.value.addressId
  }
})

useHead({ title: '結帳' })
</script>

<template>
  <main class="mx-auto max-w-3xl px-5 py-10">
    <NuxtLink to="/cart" class="text-sm text-[var(--ink-muted)] hover:underline">
      ← 回購物車
    </NuxtLink>
    <h1 class="mt-4 text-3xl font-black tracking-tight">結帳</h1>

    <AuthPanel v-if="!auth.isAuthenticated" class="mt-8" />

    <template v-else-if="items.length > 0">
      <section class="mt-8" aria-labelledby="items-heading">
        <h2 id="items-heading" class="text-sm font-semibold text-[var(--ink-muted)]">
          訂單內容
        </h2>
        <ul class="mt-3 flex flex-col gap-2">
          <li
            v-for="item in items"
            :key="item.skuId"
            class="flex items-baseline justify-between gap-4 rounded border border-[var(--line)]
                   bg-[var(--surface)] p-4"
          >
            <div>
              <div class="font-medium">{{ item.productName }}</div>
              <div class="mt-1 text-sm text-[var(--ink-muted)]">
                {{ item.specDisplay }}
                <span v-if="!item.purchasable" class="text-[var(--danger)]">（已下架）</span>
              </div>
            </div>
            <div class="tabular font-mono">
              NT$ {{ item.unitPrice.toLocaleString() }} × {{ item.quantity }}
            </div>
          </li>
        </ul>
      </section>

      <section class="mt-8" aria-labelledby="address-heading">
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
              <span class="mt-1 block text-sm text-[var(--ink-muted)]">
                {{ address.fullAddress }}
              </span>
            </span>
          </label>
        </div>
        <p v-else class="mt-3 text-sm text-[var(--ink-muted)]">
          還沒有收貨地址，
          <NuxtLink to="/addresses" class="text-[var(--accent)] hover:underline">
            先新增一筆
          </NuxtLink>
          才能結帳。
        </p>
      </section>

      <p class="mt-8 text-right font-mono text-2xl font-bold">
        NT$ {{ (cart.remote?.totalAmount ?? 0).toLocaleString() }}
      </p>

      <button
        type="button" :disabled="!canSubmit"
        class="mt-6 w-full rounded bg-[var(--accent)] px-6 py-4 font-semibold text-white
               transition disabled:cursor-not-allowed disabled:opacity-40"
        @click="submit"
      >
        {{ submitting ? '處理中⋯' : '確認下單' }}
      </button>

      <p v-if="error" class="mt-4 text-sm text-[var(--danger)]" role="alert">{{ error }}</p>
    </template>

    <p v-else class="mt-8 text-[var(--ink-muted)]">
      購物車是空的，
      <NuxtLink to="/products" class="text-[var(--accent)] hover:underline">去逛逛</NuxtLink>。
    </p>
  </main>
</template>
