<script setup lang="ts">
/**
 * 建立商品。
 *
 * **規格至少一列，而且刪不到零。** 沒有 SKU 的商品不是「還沒建完」，
 * 而是一個永遠不會出現在任何地方的殘骸——列表要顯示最低價，
 * 而最低價來自 SKU（ADR-0015 決策 5）。表單一開始就給一列，
 * 只剩一列時隱藏刪除鈕，讓「零個規格」在介面上不可能發生。
 *
 * 規格屬性用**鍵值對**而不是固定欄位：不同品類的規格維度本來就不同，
 * 把「容量」寫成欄位的那一刻，賣衣服就得改 schema。
 */
const props = defineProps<{
  categories: Array<{ id: number, label: string }>
}>()

const emit = defineEmits<{
  submit: [payload: unknown]
  cancel: []
}>()

interface AttributeRow {
  key: string
  value: string
}

interface SkuRow {
  attributes: AttributeRow[]
  price: string
  barcode: string
}

const categoryId = ref<number | null>(null)
const name = ref('')
const brand = ref('')
const description = ref('')
const skus = ref<SkuRow[]>([blankSku()])
const submitting = ref(false)

function blankSku(): SkuRow {
  return { attributes: [{ key: '', value: '' }], price: '', barcode: '' }
}

function addSku() {
  skus.value = [...skus.value, blankSku()]
}

function removeSku(index: number) {
  skus.value = skus.value.filter((_, i) => i !== index)
}

function addAttribute(sku: SkuRow) {
  sku.attributes.push({ key: '', value: '' })
}

function removeAttribute(sku: SkuRow, index: number) {
  sku.attributes.splice(index, 1)
}

/**
 * 每一列規格都要：至少一組完整的屬性、以及一個大於 0 的價格。
 *
 * 在**送出前**檢查而不是等後端回 400：後端的錯誤訊息只能說
 * 「第幾個規格有問題」，而使用者要自己數到第幾列。
 * 這不是把驗證搬到前端——後端那一份仍然是權威，這裡只是讓錯誤早一點被看見。
 */
const canSubmit = computed(() =>
  categoryId.value !== null
  && name.value.trim().length > 0
  && skus.value.length > 0
  && skus.value.every((sku) =>
    Number(sku.price) > 0
    && sku.attributes.some((attr) => attr.key.trim() && attr.value.trim()))
  && !submitting.value)

function submit() {
  if (!canSubmit.value) {
    return
  }
  submitting.value = true
  emit('submit', {
    categoryId: categoryId.value,
    name: name.value.trim(),
    brand: brand.value.trim() || null,
    description: description.value.trim() || null,
    skus: skus.value.map((sku) => ({
      attributes: Object.fromEntries(
        sku.attributes
          .filter((attr) => attr.key.trim() && attr.value.trim())
          .map((attr) => [attr.key.trim(), attr.value.trim()])),
      price: Number(sku.price),
      barcode: sku.barcode.trim() || null,
    })),
  })
  submitting.value = false
}

watchEffect(() => {
  if (categoryId.value === null && props.categories.length > 0) {
    categoryId.value = props.categories[0]!.id
  }
})
</script>

<template>
  <AppCard class="p-5 sm:p-6">
    <h2 class="eyebrow mb-4">新增商品</h2>

    <div class="grid gap-4 sm:grid-cols-2">
      <label class="flex flex-col gap-1.5">
        <span class="eyebrow">類目</span>
        <select
          v-model="categoryId"
          class="h-10 rounded-sm border border-line bg-surface px-3 text-sm"
        >
          <option v-for="option in categories" :key="option.id" :value="option.id">
            {{ option.label }}
          </option>
        </select>
      </label>

      <label class="flex flex-col gap-1.5">
        <span class="eyebrow">商品名稱</span>
        <input
          v-model="name" type="text" maxlength="128"
          class="h-10 rounded-sm border border-line bg-surface px-3 text-sm"
        >
      </label>

      <label class="flex flex-col gap-1.5">
        <span class="eyebrow">品牌</span>
        <input
          v-model="brand" type="text" maxlength="64"
          class="h-10 rounded-sm border border-line bg-surface px-3 text-sm"
        >
      </label>

      <label class="flex flex-col gap-1.5 sm:col-span-2">
        <span class="eyebrow">商品描述</span>
        <textarea
          v-model="description" rows="3" maxlength="1000"
          class="resize-y rounded-sm border border-line bg-surface p-3 text-sm leading-relaxed"
        />
      </label>
    </div>

    <section class="mt-6" aria-labelledby="skus-heading">
      <div class="mb-3 flex items-center justify-between">
        <h3 id="skus-heading" class="eyebrow">規格與價格</h3>
        <AppButton variant="secondary" size="sm" @click="addSku">＋ 加一個規格</AppButton>
      </div>

      <div class="flex flex-col gap-3">
        <div
          v-for="(sku, index) in skus"
          :key="index"
          class="rounded-sm border border-line bg-surface-sunken p-4"
        >
          <div class="mb-3 flex items-center justify-between">
            <span class="eyebrow">規格 {{ index + 1 }}</span>
            <!-- 只剩一列時不給刪：零個規格的商品建不起來，那不該是使用者能走到的狀態 -->
            <button
              v-if="skus.length > 1"
              type="button"
              class="rounded-sm px-1.5 py-0.5 text-xs text-ink-faint transition-colors
                     hover:text-danger"
              @click="removeSku(index)"
            >
              移除
            </button>
          </div>

          <div class="flex flex-col gap-2">
            <div
              v-for="(attr, attrIndex) in sku.attributes"
              :key="attrIndex"
              class="flex items-center gap-2"
            >
              <input
                v-model="attr.key" type="text" placeholder="屬性（例如 容量）"
                class="h-9 min-w-0 flex-1 rounded-sm border border-line bg-surface px-3 text-sm"
              >
              <input
                v-model="attr.value" type="text" placeholder="值（例如 256G）"
                class="h-9 min-w-0 flex-1 rounded-sm border border-line bg-surface px-3 text-sm"
              >
              <button
                v-if="sku.attributes.length > 1"
                type="button"
                class="shrink-0 rounded-sm px-2 py-1 text-xs text-ink-faint transition-colors
                       hover:text-danger"
                @click="removeAttribute(sku, attrIndex)"
              >
                ✕
              </button>
            </div>
            <button
              type="button"
              class="self-start rounded-sm px-1 py-0.5 text-xs text-accent transition-colors
                     hover:underline"
              @click="addAttribute(sku)"
            >
              ＋ 加一個屬性
            </button>
          </div>

          <div class="mt-3 flex flex-wrap gap-3">
            <label class="flex flex-col gap-1.5">
              <span class="eyebrow">價格</span>
              <input
                v-model="sku.price" type="number" min="1" step="1" placeholder="0"
                class="figure h-9 w-32 rounded-sm border border-line bg-surface px-3 text-sm"
              >
            </label>
            <label class="flex flex-col gap-1.5">
              <span class="eyebrow">條碼（選填）</span>
              <input
                v-model="sku.barcode" type="text" maxlength="64"
                class="figure h-9 w-44 rounded-sm border border-line bg-surface px-3 text-sm"
              >
            </label>
          </div>
        </div>
      </div>
    </section>

    <div class="mt-6 flex items-center justify-between gap-4">
      <!-- 讓「建立之後還要上架」這件事在按下按鈕之前就講清楚 -->
      <p class="text-xs text-ink-faint">
        建立後為<b>草稿</b>，需要另外上架才會出現在商店與搜尋結果。
      </p>
      <div class="flex shrink-0 gap-2">
        <AppButton variant="secondary" size="sm" @click="emit('cancel')">取消</AppButton>
        <AppButton size="sm" :disabled="!canSubmit" @click="submit">建立商品</AppButton>
      </div>
    </div>
  </AppCard>
</template>
