<script setup lang="ts">
import type { AddressPayload, AddressView } from '~/types/api'

/**
 * 地址表單，新增與編輯共用。
 *
 * 欄位保持結構化（縣市／區／街道分開）而不是一個大文字框——
 * 物流 API 要的是分開的欄位，事後從一整串地址切回來是猜測，不是解析。
 */
const props = defineProps<{ initial?: AddressView | null; submitting?: boolean }>()
const emit = defineEmits<{ submit: [AddressPayload]; cancel: [] }>()

const form = reactive<AddressPayload>({
  recipientName: '',
  phone: '',
  postalCode: '',
  region: '',
  district: '',
  streetAddress: '',
  defaultAddress: false,
})

watchEffect(() => {
  if (props.initial) {
    Object.assign(form, {
      recipientName: props.initial.recipientName,
      phone: props.initial.phone,
      postalCode: props.initial.postalCode,
      region: props.initial.region,
      district: props.initial.district,
      streetAddress: props.initial.streetAddress,
      defaultAddress: props.initial.defaultAddress,
    })
  }
})

const complete = computed(() =>
  Boolean(form.recipientName && form.phone && form.postalCode
    && form.region && form.district && form.streetAddress),
)

function submit() {
  if (complete.value) {
    emit('submit', { ...form })
  }
}
</script>

<template>
  <form class="flex flex-col gap-3" @submit.prevent="submit">
    <div class="grid gap-3 sm:grid-cols-2">
      <label class="flex flex-col gap-1 text-sm">
        <span class="eyebrow">收件人</span>
        <input
          v-model.trim="form.recipientName" required maxlength="32"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="eyebrow">聯絡電話</span>
        <input
          v-model.trim="form.phone" required
          class="figure rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
      </label>
    </div>

    <div class="grid gap-3 sm:grid-cols-3">
      <label class="flex flex-col gap-1 text-sm">
        <span class="eyebrow">郵遞區號</span>
        <input
          v-model.trim="form.postalCode" required inputmode="numeric" maxlength="6"
          class="figure rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="eyebrow">縣市</span>
        <input
          v-model.trim="form.region" required maxlength="32"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
      </label>
      <label class="flex flex-col gap-1 text-sm">
        <span class="eyebrow">鄉鎮市區</span>
        <input
          v-model.trim="form.district" required maxlength="32"
          class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
        >
      </label>
    </div>

    <label class="flex flex-col gap-1 text-sm">
      <span class="eyebrow">地址</span>
      <input
        v-model.trim="form.streetAddress" required maxlength="128"
        class="rounded-sm border border-line bg-surface px-3 py-2 text-sm"
      >
    </label>

    <label class="flex items-center gap-2 text-sm">
      <input v-model="form.defaultAddress" type="checkbox">
      <span>設為預設收貨地址</span>
    </label>

    <div class="flex gap-2">
      <AppButton type="submit" :disabled="!complete || submitting">
        {{ submitting ? '儲存中⋯' : '儲存' }}
      </AppButton>
      <AppButton variant="secondary" @click="emit('cancel')">取消</AppButton>
    </div>
  </form>
</template>
