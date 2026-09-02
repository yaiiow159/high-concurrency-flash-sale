<script setup lang="ts">
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
    actionError.value = (cause as { message?: string }).message ?? '儲存失敗'
  } finally {
    submitting.value = false
  }
}

async function handleRemove(address: AddressView) {
  actionError.value = null
  try {
    await remove(address.addressId)
  } catch (cause) {
    actionError.value = (cause as { message?: string }).message ?? '刪除失敗'
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
  <main class="mx-auto max-w-3xl px-5 py-10">
    <header class="flex flex-wrap items-baseline justify-between gap-3">
      <h1 class="text-3xl font-black tracking-tight">收貨地址</h1>
      <NuxtLink to="/products" class="text-sm text-[var(--accent)] hover:underline">
        繼續購物 →
      </NuxtLink>
    </header>

    <p class="mt-2 text-sm text-[var(--ink-muted)]">
      修改或刪除地址<b>不會影響已成立的訂單</b>——訂單存的是下單當下的快照。
    </p>

    <template v-if="auth.isAuthenticated">
      <p v-if="loading" class="mt-6 text-[var(--ink-muted)]">載入中⋯</p>
      <p v-else-if="error" class="mt-6 text-[var(--danger)]" role="alert">{{ error }}</p>

      <ul v-else class="mt-6 flex flex-col gap-3">
        <li
          v-for="address in addresses"
          :key="address.addressId"
          class="rounded border border-[var(--line)] bg-[var(--surface)] p-5"
          :class="address.defaultAddress ? 'border-[var(--accent)]' : ''"
        >
          <div class="flex flex-wrap items-baseline justify-between gap-2">
            <div>
              <span class="font-semibold">{{ address.recipientName }}</span>
              <span class="ml-3 font-mono text-sm text-[var(--ink-muted)]">
                {{ address.phone }}
              </span>
            </div>
            <span
              v-if="address.defaultAddress"
              class="rounded border border-[var(--accent)] px-2 py-0.5 text-xs text-[var(--accent)]"
            >
              預設
            </span>
          </div>
          <p class="mt-2 text-[var(--ink-muted)]">{{ address.fullAddress }}</p>

          <div class="mt-4 flex flex-wrap gap-3 text-sm">
            <button
              v-if="!address.defaultAddress" type="button"
              class="text-[var(--accent)] hover:underline"
              @click="setDefault(address.addressId)"
            >
              設為預設
            </button>
            <button
              type="button" class="text-[var(--accent)] hover:underline"
              @click="startEdit(address)"
            >
              編輯
            </button>
            <button
              type="button" class="text-[var(--danger)] hover:underline"
              @click="handleRemove(address)"
            >
              刪除
            </button>
          </div>
        </li>
      </ul>

      <p v-if="!loading && !error && addresses.length === 0" class="mt-6 text-[var(--ink-muted)]">
        還沒有收貨地址，新增一筆才能下單。
      </p>

      <p v-if="actionError" class="mt-4 text-sm text-[var(--danger)]" role="alert">
        {{ actionError }}
      </p>

      <section v-if="showForm" class="mt-6 rounded border border-[var(--line)] bg-[var(--surface)] p-5">
        <h2 class="mb-4 font-semibold">{{ editing ? '編輯地址' : '新增地址' }}</h2>
        <AddressForm
          :initial="editing" :submitting="submitting"
          @submit="handleSubmit" @cancel="closeForm"
        />
      </section>
      <button
        v-else type="button"
        class="mt-6 rounded border border-[var(--line)] px-5 py-2.5 transition
               hover:border-[var(--accent)]"
        @click="showForm = true"
      >
        ＋ 新增地址
      </button>
    </template>

    <AuthPanel v-else class="mt-10" />
  </main>
</template>
