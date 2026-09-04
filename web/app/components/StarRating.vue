<script setup lang="ts">
/**
 * 星等。同時是「顯示」與「輸入」兩種用途。
 *
 * **平均分要能顯示半顆星。** 4.3 分畫成 4 顆或 5 顆都是在說謊，
 * 而電商的評分摘要正是靠那個小數點取信於人。做法是疊兩層星星，
 * 上層用 `width` 裁切——比用「半星圖示」通吃任意比例，
 * 也不必為 4.3 與 4.7 準備兩種圖。
 *
 * **輸入模式是原生 radio。** 用 div + click 做出來的星等在鍵盤與
 * 螢幕閱讀器上完全不存在，而評價表單是少數使用者真的會用鍵盤填的表單
 * （打完長文字後習慣用 Tab 移動）。radio 讓方向鍵、Tab、
 * 以及「五選一」的語意全部免費得到。
 *
 * **模板必須是單一根節點，連根層的註解都不能有。**
 * 先前是 `<fieldset v-if>` 與 `<div v-else>` 兩個並列的根，
 * 那讓 Vue 把這個元件當成 fragment，而 fragment 拿不到父層傳進來的 class：
 * 伺服器渲染出 `class="relative inline-flex"`、客戶端卻是
 * `class="relative inline-flex justify-center opacity-40"`，於是 hydration mismatch。
 *
 * 修好之後又踩了一次同一個坑——根節點上方留了一段 HTML 註解，
 * 而註解在開發模式下是真實的節點，元件因此又變回 fragment。
 * 說明文字要寫在這裡，不是寫在 `<template>` 的根層。
 */
const props = withDefaults(defineProps<{
  /** 顯示模式的分數，可含小數 */
  value?: number
  size?: 'sm' | 'md' | 'lg' | 'xl'
  /** 輸入模式：與 v-model 搭配 */
  interactive?: boolean
  /** 輸入模式的欄位名稱，同一頁有多組時必須不同 */
  name?: string
}>(), { value: 0, size: 'md', interactive: false, name: 'rating' })

const model = defineModel<number>({ default: 0 })

const SIZES = {
  sm: 'h-3.5 w-3.5',
  md: 'h-4 w-4',
  lg: 'h-5 w-5',
  xl: 'h-7 w-7',
} as const

const STARS = [1, 2, 3, 4, 5]

/** 滑鼠停留時預覽的分數；離開後回到已選的值。 */
const hovered = ref<number | null>(null)
const shown = computed(() => hovered.value ?? model.value)

/** 顯示模式：夾在 0–5 之後換算成寬度百分比。 */
const fillWidth = computed(() => `${Math.max(0, Math.min(5, props.value)) / 5 * 100}%`)

const LABELS = ['', '很差', '普通', '還可以', '不錯', '很好'] as const
</script>

<template>
  <div :class="interactive ? 'inline-flex items-center gap-2' : 'relative inline-flex shrink-0'">
    <!-- 輸入模式：原生 radio，鍵盤與螢幕閱讀器才拿得到它 -->
    <fieldset v-if="interactive" class="flex items-center gap-2">
      <legend class="sr-only">評分</legend>
      <div class="flex items-center gap-0.5" @mouseleave="hovered = null">
        <label
          v-for="star in STARS"
          :key="star"
          class="cursor-pointer p-0.5 transition-transform hover:scale-110"
          :aria-label="`${star} 星`"
          @mouseenter="hovered = star"
        >
          <input
            v-model.number="model" type="radio" :name="name" :value="star"
            class="peer sr-only"
          >
          <svg
            :class="[SIZES[size], 'transition-colors peer-focus-visible:outline'
              + ' peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2']"
            :style="{ outlineColor: 'var(--cta)' }"
            viewBox="0 0 20 20" aria-hidden="true"
          >
            <path
              d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L10 14.9 4.8 17.6l1-5.8L1.5 7.7l5.9-.9z"
              :fill="star <= shown ? 'var(--star)' : 'var(--star-empty)'"
            />
          </svg>
        </label>
      </div>
      <!--
        文字提示與星星並存，不是二選一。只有星星時使用者得自己數，
        而「四顆星是不錯還是很好」在不同網站上的答案不一樣
      -->
      <span class="text-sm text-ink-muted" aria-live="polite">
        {{ shown > 0 ? LABELS[shown] : '請選擇評分' }}
      </span>
    </fieldset>

    <!-- 顯示模式：兩層疊放，上層裁切出小數 -->
    <template v-else>
      <div
        class="flex items-center gap-0.5"
        role="img"
        :aria-label="`五顆星中的 ${value.toFixed(1)} 顆`"
      >
        <svg
          v-for="star in STARS" :key="star" :class="SIZES[size]"
          viewBox="0 0 20 20" aria-hidden="true"
        >
          <path
            d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L10 14.9 4.8 17.6l1-5.8L1.5 7.7l5.9-.9z"
            fill="var(--star-empty)"
          />
        </svg>
      </div>
      <div
        class="absolute inset-y-0 left-0 overflow-hidden"
        :style="{ width: fillWidth }"
        aria-hidden="true"
      >
        <div class="flex items-center gap-0.5">
          <svg
            v-for="star in STARS" :key="star" :class="SIZES[size]"
            viewBox="0 0 20 20"
          >
            <path
              d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L10 14.9 4.8 17.6l1-5.8L1.5 7.7l5.9-.9z"
              fill="var(--star)"
            />
          </svg>
        </div>
      </div>
    </template>
  </div>
</template>
