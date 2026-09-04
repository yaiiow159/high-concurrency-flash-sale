<script setup lang="ts">
/**
 * 後台的狀態分頁。
 *
 * 用 `role="tablist"` 而不是一排 button：這是「同一份清單的不同切面」，
 * 而不是五個獨立的動作。螢幕閱讀器會因此念出「第 2 個，共 4 個」，
 * 鍵盤使用者也知道左右鍵能切換。
 *
 * **不做「全部」這個分頁。** 後台的每一種狀態對應一種待辦，
 * 混在一起之後就沒有任何一列是「該處理的」——那正是工作佇列
 * 退化成資料表的方式。
 */
defineProps<{
  tabs: ReadonlyArray<{ value: string, label: string }>
}>()

const model = defineModel<string>({ required: true })
</script>

<template>
  <div
    class="scroll-x flex gap-1 border-b border-line"
    role="tablist"
  >
    <button
      v-for="item in tabs"
      :key="item.value"
      type="button"
      role="tab"
      :aria-selected="model === item.value"
      class="-mb-px shrink-0 whitespace-nowrap border-b-2 px-3.5 py-2.5 text-sm
             transition-colors"
      :class="model === item.value
        ? 'border-accent font-medium text-accent'
        : 'border-transparent text-ink-muted hover:text-ink'"
      @click="model = item.value"
    >
      {{ item.label }}
    </button>
  </div>
</template>
