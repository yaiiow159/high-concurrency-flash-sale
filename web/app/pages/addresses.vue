<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAddresses } from '~/composables/useAddresses'
import { useAuthStore } from '~/stores/auth'
import type { AddressPayload, AddressView } from '~/types/api'

/**
 * 收貨地址簿。
 *
 * <b>不做 SSR、不做 ISR</b>——地址是個資，進了被快取的 HTML 就等於
 * 發給下一個訪客。資料一律在客戶端掛載後才取。
 */
const auth = useAuthStore()
const { addresses, loading, error, load, add, update, remove, setDefault } = useAddresses()

const editing = ref<AddressView | null>(null)
const showForm = ref(false)
const submitting = ref(false)
const actionError = ref<string | null>(null)

async function handleSubmit(payload: AddressPayload) {
  submitting.value = true
  actionError.value = null
  try {
    if (editing.value) {
      await update(editing.value.addressId, payload)
    } else {
      await add(payload)
    }
    closeForm()
  } catch (cause) {
    actionError.value = errorMessage(cause, '儲存失敗')
  } finally {
    submitting.value = false
  }
}

async function handleRemove(address: AddressView) {
  actionError.value = null
  try {
    await remove(address.addressId)
  } catch (cause) {
    actionError.value = errorMessage(cause, '刪除失敗')
  }
}

function startEdit(address: AddressView) {
  editing.value = address
  showForm.value = true
}

function closeForm() {
  editing.value = null
  showForm.value = false
}

onMounted(() => {
  if (auth.isAuthenticated) {
    load()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    load()
  }
})

useHead({ title: '收貨地址' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Addresses"
      title="收貨地址"
      description="修改或刪除地址不會影響已成立的訂單——訂單存的是下單當下的快照。"
    />

    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <div v-if="loading" class="grid gap-3 sm:grid-cols-2">
        <SkeletonCard v-for="n in 2" :key="n" variant="row" />
      </div>
      <p v-else-if="error" class="text-danger" role="alert">{{ error }}</p>

      <ul v-else-if="addresses.length > 0" class="grid gap-3 sm:grid-cols-2">
        <li v-for="address in addresses" :key="address.addressId">
          <AppCard :highlighted="address.defaultAddress" class="flex h-full flex-col p-5">
            <div class="flex items-start justify-between gap-3">
              <div>
                <p class="font-medium">{{ address.recipientName }}</p>
                <p class="figure mt-1 text-sm text-ink-muted">{{ address.phone }}</p>
              </div>
              <span
                v-if="address.defaultAddress"
                class="rounded-sm border border-accent px-2 py-0.5 text-xs text-accent"
              >
                預設
              </span>
            </div>
            <p class="mt-3 flex-1 text-sm text-ink-muted">{{ address.fullAddress }}</p>

            <div class="mt-4 flex flex-wrap gap-1 border-t border-line pt-3">
              <AppButton
                v-if="!address.defaultAddress" variant="ghost" size="sm"
                @click="setDefault(address.addressId)"
              >
                設為預設
              </AppButton>
              <AppButton variant="ghost" size="sm" @click="startEdit(address)">編輯</AppButton>
              <AppButton variant="danger" size="sm" @click="handleRemove(address)">刪除</AppButton>
            </div>
          </AppCard>
        </li>
      </ul>

      <EmptyState v-else title="還沒有收貨地址。" hint="新增一筆才能下單。" />

      <p v-if="actionError" class="mt-4 text-sm text-danger" role="alert">{{ actionError }}</p>

      <AppCard v-if="showForm" class="mt-6 max-w-prose p-6">
        <h2 class="mb-5 font-semibold">{{ editing ? '編輯地址' : '新增地址' }}</h2>
        <AddressForm
          :initial="editing" :submitting="submitting"
          @submit="handleSubmit" @cancel="closeForm"
        />
      </AppCard>
      <AppButton v-else class="mt-6" variant="secondary" @click="showForm = true">
        ＋ 新增地址
      </AppButton>
    </template>
  </div>
</template>
