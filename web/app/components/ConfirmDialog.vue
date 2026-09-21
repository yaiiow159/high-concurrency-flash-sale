<script setup lang="ts">
/**
 * 確認對話框。用原生 `<dialog>`：焦點鎖定、Esc 關閉、背景不可互動都是瀏覽器給的，
 * 自己用 div 疊出來的版本這三件事通常會漏掉至少一件。
 */
const props = withDefaults(defineProps<{
  title: string
  confirmLabel?: string
  cancelLabel?: string
  /** 進行中：兩顆按鈕都鎖住，也不讓 Esc 關掉——請求已經送出去了 */
  busy?: boolean
}>(), { confirmLabel: '確定', cancelLabel: '先不要', busy: false })

const open = defineModel<boolean>('open', { required: true })
const emit = defineEmits<{ confirm: [] }>()

const dialog = ref<HTMLDialogElement | null>(null)

watch(open, (next) => {
  const element = dialog.value
  if (!element) {
    return
  }
  if (next && !element.open) {
    element.showModal()
  } else if (!next && element.open) {
    element.close()
  }
})

function onCancel(event: Event) {
  if (props.busy) {
    event.preventDefault()
  }
}

/** 點到對話框本體以外（也就是 backdrop）才關。 */
function onBackdropClick(event: MouseEvent) {
  if (event.target === dialog.value && !props.busy) {
    open.value = false
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="w-[min(26rem,calc(100vw-2rem))] rounded bg-surface p-0 text-ink shadow-lift
           backdrop:bg-ink-inverse/50 backdrop:backdrop-blur-[2px]"
    @close="open = false"
    @cancel="onCancel"
    @click="onBackdropClick"
  >
    <div class="p-6">
      <h2 class="text-lg font-bold">{{ title }}</h2>
      <div class="mt-2 text-sm leading-relaxed text-ink-muted">
        <slot />
      </div>
      <div class="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <!-- 不做的那一顆排在前面且先取得焦點：誤按 Enter 的代價應該是「沒事」 -->
        <AppButton variant="secondary" :disabled="busy" autofocus @click="open = false">
          {{ cancelLabel }}
        </AppButton>
        <AppButton :disabled="busy" @click="emit('confirm')">
          {{ busy ? '處理中⋯' : confirmLabel }}
        </AppButton>
      </div>
    </div>
  </dialog>
</template>
