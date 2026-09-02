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
  ['#dce9ea', '#a9c9cd'], // 淺青
  ['#e4e6f0', '#b3b8d4'], // 霧藍紫
  ['#e7ece2', '#bacaae'], // 灰綠
  ['#f0e9df', '#d5c3a8'], // 砂
  ['#efe3e0', '#d3b0a6'], // 陶土
  ['#e2eaf0', '#aac2d4'], // 石板藍
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
    <span class="text-4xl font-bold tracking-tight text-black/30 sm:text-5xl">
      {{ initial }}
    </span>
  </div>
</template>

<style scoped>
/*
 * 深色模式壓暗。
 *
 * 這些色塊是淺色系的裝飾方塊，不是商品照片——照片在深色介面上維持原亮度是對的，
 * 但一整排淺色方塊放在深底上會刺眼到蓋過價格。
 * 用 filter 而不是另備一組深色色盤：色盤要維護兩份，而這裡要的只是「暗一點」。
 */
:root:not([data-theme='light']) .tile {
  @media (prefers-color-scheme: dark) {
    filter: brightness(0.62) saturate(0.85);
  }
}

:root[data-theme='dark'] .tile {
  filter: brightness(0.62) saturate(0.85);
}
</style>
