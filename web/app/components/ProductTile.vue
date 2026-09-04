<script setup lang="ts">
/**
 * 商品視覺。
 *
 * <p><b>目錄目前沒有圖片欄位</b>（那屬於 P4 的營運後台）。
 * 但純文字的商品格在網格裡看起來像後台列表，不像商店——
 * 電商的商品格本來就是圖片主導的。
 *
 * <p>折衷是從 SKU/商品 ID 推導一個<b>確定性</b>的色塊：
 * 同一個商品永遠得到同一個視覺，重整頁面不會變色。
 * 這比放一個灰色佔位框誠實——它不假裝有圖片，
 * 但給了網格該有的視覺重量與可辨識性。
 *
 * <p><b>用手挑的色盤而不是 HSL 公式。</b>照公式繞色環算出來的顏色
 * 十之八九是濁的，而且相鄰的商品會撞成同一種灰紫。
 * 六個挑過的色調彼此分得開，看起來像是設計過的，而不是隨機生成的。
 */
const props = withDefaults(defineProps<{
  seed: number | string
  label?: string
  ratio?: 'square' | 'wide'
}>(), { ratio: 'square' })

/**
 * 六個色調，彼此在明度與色相上都拉開距離。
 * 飽和度刻意壓低——這些方塊是背景，不該蓋過價格與商品名。
 */
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
    :style="style"
    aria-hidden="true"
  >
    <span
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
