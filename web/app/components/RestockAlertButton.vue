<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'

/** 缺貨時的「有貨通知我」。有貨時這顆按鈕不該出現——那時該按的是購買。 */
const props = defineProps<{ skuId: number }>()

const { request } = useApi()
const auth = useAuthStore()

const pending = ref<Set<number>>(new Set())
const busy = ref(false)
const message = ref('')

const subscribed = computed(() => pending.value.has(props.skuId))

async function load() {
  if (!auth.isAuthenticated) {
    return
  }
  try {
    pending.value = new Set(
      await request<number[]>('/api/v1/restock-alerts', { authenticated: true }))
  } catch {
    pending.value = new Set()
  }
}

async function toggle() {
  if (!auth.isAuthenticated) {
    message.value = '請先登入'
    return
  }
  busy.value = true
  message.value = ''
  try {
    const next = !subscribed.value
    await request<void>(`/api/v1/restock-alerts/${props.skuId}`,
      { method: next ? 'POST' : 'DELETE', authenticated: true })
    const updated = new Set(pending.value)
    if (next) {
      updated.add(props.skuId)
    } else {
      updated.delete(props.skuId)
    }
    pending.value = updated
    message.value = next ? '到貨時會通知你' : '已取消通知'
  } catch (cause) {
    message.value = errorMessage(cause, '操作失敗')
  } finally {
    busy.value = false
  }
}

onMounted(load)
watch(() => auth.isAuthenticated, load)
</script>

<template>
  <div class="flex flex-col gap-1.5">
    <AppButton
      variant="secondary"
      :disabled="busy"
      @click="toggle"
    >
      {{ subscribed ? '已訂閱到貨通知' : '有貨通知我' }}
    </AppButton>
    <p v-if="message" class="text-xs text-ink-faint">{{ message }}</p>
  </div>
</template>
