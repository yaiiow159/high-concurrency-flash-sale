<script setup lang="ts">
/** 商品視覺。 */
const props = withDefaults(defineProps<{
  seed: number | string
  label?: string
  ratio?: 'square' | 'wide'
  /** 真實圖片網址（ADR-0027）。有圖就顯示圖，沒有才退回色塊—— **色塊是後備而不是預設**：先前是唯一選項，現在它的角色變成 「這個商品還沒上圖」的誠實表示。 */
  src?: string | null
}>(), { ratio: 'square', src: null })

/** 圖片載不出來時退回色塊。 物件被誤刪、CDN 出問題、網路中斷都會走到這裡， 而瀏覽器預設的破圖圖示比一個乾淨的色塊難看得多。 */
const failed = ref(false)
watch(() => props.src, () => { failed.value = false })
const showImage = computed(() => Boolean(props.src) && !failed.value)

/** 六個色調，彼此在明度與色相上都拉開距離。 飽和度刻意壓低——這些方塊是背景，不該蓋過價格與商品名。 */
const PALETTE = [
  ['#e8f2f3', '#b6d4d8'], // 淺青
  ['#ecedf6', '#bfc4dd'], // 霧藍紫
  ['#eef3e9', '#c6d5ba'], // 灰綠
  ['#f6f0e7', '#dfceb5'], // 砂
  ['#f5eae7', '#debdb3'], // 陶土
  ['#ebf1f6', '#b8cbdb'], // 石板藍
] as const

/** 簡單的字串雜湊。不需要密碼學強度，只要同一個輸入永遠給同一個輸出。 */
function hash(value: number | string): number {
  const text = String(value)
  let result = 0
  for (let i = 0; i < text.length; i++) {
    result = (result * 31 + text.charCodeAt(i)) >>> 0
  }
  return result
}

const style = computed(() => {
  const [from, to] = PALETTE[hash(props.seed) % PALETTE.length]!
  return { background: `linear-gradient(140deg, ${from} 0%, ${to} 100%)` }
})

/** 取商品名的第一個字當標記；中文取一字、拉丁取兩字 */
const initial = computed(() => {
  const text = (props.label ?? '').trim()
  if (!text) {
    return ''
  }
  return /[一-鿿]/.test(text[0]!) ? text[0]! : text.slice(0, 2).toUpperCase()
})
</script>

<template>
  <div
    class="tile relative flex items-center justify-center overflow-hidden rounded-sm"
    :class="ratio === 'square' ? 'aspect-square' : 'aspect-[16/10]'"
    :style="showImage ? undefined : style"
    :aria-hidden="showImage ? undefined : 'true'"
  >
    <img
      v-if="showImage"
      :src="src!"
      :alt="label ?? ''"
      loading="lazy"
      decoding="async"
      class="h-full w-full object-cover"
      @error="failed = true"
    >
    <span
      v-else
      class="flex h-14 w-14 items-center justify-center rounded-full bg-white/45
             text-xl font-semibold tracking-tight text-black/45
             backdrop-blur-[1px] sm:h-16 sm:w-16 sm:text-2xl"
    >
      {{ initial }}
    </span>
  </div>
</template>

<style scoped>
/*
 * 頂部一道極淡的高光，讓方塊看起來有受光面而不是一片平色。
 * 這是它唯一的「材質」，其餘全靠色盤。
 */
.tile::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgb(255 255 255 / 28%) 0%, transparent 45%);
  pointer-events: none;
}
</style>
