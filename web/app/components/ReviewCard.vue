<script setup lang="ts">
import type { ReviewView } from '~/types/api'

/**
 * 一則評價。
 *
 * **頭像用名字的第一個字，不是灰色人形圖示。**
 * 一整排相同的灰人形會讓評價區看起來像是機器產生的；
 * 用首字加上由作者名決定的底色，每一則就有了辨識度，
 * 而那正是「這是真人寫的」這個印象的來源。
 * 色相由名字雜湊而來——同一個作者永遠是同一個顏色。
 *
 * **「已編輯」要標出來。** 讀者有權知道他看的不是原始版本。
 * 藏起來省不了什麼，被發現卻會直接損害整個評價區的可信度。
 */
const props = defineProps<{
  review: ReviewView
  /** 顯示「編輯」按鈕（只在「我的評價」清單） */
  showEdit?: boolean
}>()

defineEmits<{ edit: [review: ReviewView] }>()

/** 首字。中文取一字、拉丁取一字並轉大寫。 */
const initial = computed(() => {
  const text = props.review.authorName.trim()
  return text ? text[0]!.toUpperCase() : '?'
})

/**
 * 頭像底色。
 *
 * 用固定的六個色相而不是 `hsl(hash % 360)`：繞色環算出來的顏色
 * 十之八九是濁的，而且相鄰的兩則評價很容易撞成同一種灰紫。
 * 這與 `ProductTile` 的色盤是同一個判斷。
 */
const AVATAR_HUES = [
  'oklch(0.72 0.11 20)', // 陶土
  'oklch(0.72 0.10 145)', // 灰綠
  'oklch(0.70 0.11 250)', // 石板藍
  'oklch(0.74 0.10 80)', // 砂金
  'oklch(0.70 0.10 320)', // 霧紫
  'oklch(0.72 0.11 195)', // 淺青
] as const

const avatarColor = computed(() => {
  const text = props.review.authorName
  let hash = 0
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  }
  return AVATAR_HUES[hash % AVATAR_HUES.length]!
})

function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString('zh-TW', {
    year: 'numeric', month: '2-digit', day: '2-digit',
  })
}
</script>

<template>
  <article class="flex gap-3.5 py-5">
    <span
      class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full
             text-sm font-semibold text-white/95"
      :style="{ background: avatarColor }"
      aria-hidden="true"
    >{{ initial }}</span>

    <div class="min-w-0 flex-1">
      <header class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
        <span class="text-sm font-medium">{{ review.authorName }}</span>
        <StarRating :value="review.stars" size="sm" />
        <span class="figure text-xs text-ink-faint">{{ formatDate(review.createdAt) }}</span>
        <span
          v-if="review.edited"
          class="rounded-sm bg-surface-sunken px-1.5 py-0.5 text-[11px] text-ink-faint"
        >已編輯</span>

        <button
          v-if="showEdit && review.editable"
          type="button"
          class="ml-auto rounded-sm px-1.5 py-0.5 text-xs text-accent transition-colors
                 hover:bg-accent-soft"
          @click="$emit('edit', review)"
        >
          編輯
        </button>
        <!--
          過了修改窗口就說明原因，不是讓按鈕安靜消失。
          消失的話使用者只會反覆找那個他記得存在過的按鈕
        -->
        <span
          v-else-if="showEdit"
          class="ml-auto text-xs text-ink-faint"
        >已超過七天修改期限</span>
      </header>

      <!-- whitespace-pre-line：使用者打的換行是內容的一部分，不是雜訊 -->
      <p class="mt-2 whitespace-pre-line text-sm leading-relaxed text-ink-muted">
        {{ review.content }}
      </p>
    </div>
  </article>
</template>
