import { useApi } from '~/composables/useApi'
import type { AddressPayload, AddressView } from '~/types/api'

/**
 * 收貨地址簿。
 *
 * 這裡的資料<b>永遠不做 SSR</b>：地址是個資，一旦進了被 ISR 快取的 HTML，
 * 就等於發給下一個訪客。所有讀取都在客戶端掛載後才發生。
 */
export function useAddresses() {
  const { request } = useApi()

  const addresses = ref<AddressView[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  const defaultAddress = computed(
    () => addresses.value.find((address) => address.defaultAddress) ?? null,
  )

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      addresses.value = await request<AddressView[]>('/api/v1/addresses', {
        authenticated: true,
      })
    } catch (cause) {
      error.value = (cause as { message?: string }).message ?? '無法載入地址'
    } finally {
      loading.value = false
    }
  }

  /**
   * 每個寫入操作後都重新載入整份清單。
   *
   * 看似浪費，但「設為預設」會同時改動其他筆的旗標——
   * 在前端自行推算哪幾筆該翻轉，等於把後端的不變式複製一份到這裡，
   * 而那份副本遲早會與後端漂移。地址簿最多 20 筆，重讀的成本可以忽略。
   */
  async function add(payload: AddressPayload): Promise<void> {
    await request<AddressView>('/api/v1/addresses', {
      method: 'POST', authenticated: true, body: payload,
    })
    await load()
  }

  async function update(addressId: number, payload: AddressPayload): Promise<void> {
    await request<AddressView>(`/api/v1/addresses/${addressId}`, {
      method: 'PUT', authenticated: true, body: payload,
    })
    await load()
  }

  async function remove(addressId: number): Promise<void> {
    await request<void>(`/api/v1/addresses/${addressId}`, {
      method: 'DELETE', authenticated: true,
    })
    await load()
  }

  async function setDefault(addressId: number): Promise<void> {
    await request<AddressView>(`/api/v1/addresses/${addressId}/default`, {
      method: 'POST', authenticated: true,
    })
    await load()
  }

  return {
    addresses: readonly(addresses),
    defaultAddress,
    loading: readonly(loading),
    error: readonly(error),
    load,
    add,
    update,
    remove,
    setDefault,
  }
}
