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
  <div>
    <PageHeader eyebrow="Checkout" title="結帳">
      <template #actions>
        <NuxtLink to="/cart" class="text-sm text-ink-muted transition-colors hover:text-ink">
          ← 回購物車
        </NuxtLink>
      </template>
    </PageHeader>

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <div
      v-else-if="items.length > 0"
      class="grid gap-8 lg:grid-cols-[1fr_20rem] lg:items-start"
    >
      <div class="flex flex-col gap-8">
        <section aria-labelledby="items-heading">
          <h2 id="items-heading" class="eyebrow mb-3">訂單內容</h2>
          <ul class="flex flex-col gap-2">
            <li v-for="item in items" :key="item.skuId">
              <AppCard class="flex flex-wrap items-baseline justify-between gap-4 p-4">
                <div>
                  <p class="font-medium">{{ item.productName }}</p>
                  <p class="mt-1 text-sm text-ink-muted">
                    {{ item.specDisplay }}
                    <span v-if="!item.purchasable" class="text-danger">（已下架）</span>
                  </p>
                </div>
                <p class="flex items-baseline gap-1.5 text-sm text-ink-muted">
                  <MoneyText :amount="item.unitPrice" size="sm" tone="muted" />
                  <span class="figure">× {{ item.quantity }}</span>
                </p>
              </AppCard>
            </li>
          </ul>
        </section>

        <section aria-labelledby="address-heading">
          <h2 id="address-heading" class="eyebrow mb-3">寄送至</h2>
          <div v-if="addresses.length > 0" class="flex flex-col gap-2">
            <label
              v-for="address in addresses"
              :key="address.addressId"
              class="flex cursor-pointer items-start gap-3 rounded-sm border p-4
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
          </div>
          <p v-else class="text-sm text-ink-muted">
            還沒有收貨地址，
            <NuxtLink to="/addresses" class="text-accent hover:underline">先新增一筆</NuxtLink>
            才能結帳。
          </p>
        </section>
      </div>

      <AppCard class="p-5 lg:sticky lg:top-24">
        <h2 class="eyebrow mb-4">應付金額</h2>
        <MoneyText :amount="cart.remote?.totalAmount" size="xl" />

        <AppButton class="mt-6" size="lg" block :disabled="!canSubmit" @click="submit">
          {{ submitting ? '處理中⋯' : '確認下單' }}
        </AppButton>

        <p
          v-if="error"
          class="mt-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
          role="alert"
        >
          {{ error }}
        </p>
      </AppCard>
    </div>

    <EmptyState v-else title="購物車是空的。" hint="加點東西再回來結帳。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        去逛商品
      </AppButton>
    </EmptyState>
  </div>
</template>
