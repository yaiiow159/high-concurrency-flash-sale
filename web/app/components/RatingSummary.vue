<script setup lang="ts">
import type { ProductRatingView } from '~/types/api'

/**
 * 評分摘要：大分數 + 星等 + 分佈長條。
 *
 * 這是電商商品頁的固定語彙，照著做而不是自創——使用者已經知道怎麼讀它，
 * 換個排法只會讓他們多花時間。三個部分各自回答一個問題：
 * 分數說「好不好」、則數說「這個分數可不可信」、長條說「評價分不分歧」。
 *
 * **則數與分數一樣重要。** 4.9 分（3 則）與 4.3 分（1,842 則）
 * 是完全不同的兩件事，只顯示分數等於把後者的優勢藏起來。
 *
 * 長條的百分比由後端算好，前端不自己除——兩邊各算一次，
 * 四捨五入遲早會不同，而那會表現成「長條加起來不是 100%」。
 */
defineProps<{
  rating: ProductRatingView | null
  loading?: boolean
}>()
</script>

<template>
  <SkeletonBlock v-if="loading" class="h-32" />

  <!--
    尚無評價時給一句話，不是一個 0 分的空長條圖。
    畫一排空星星會讓使用者以為這件商品「被評了 0 分」。
  -->
  <div
    v-else-if="!rating || rating.count === 0"
    class="rounded-[--radius] border border-dashed border-line px-5 py-8 text-center"
  >
    <StarRating :value="0" size="lg" class="justify-center opacity-40" />
    <p class="mt-3 text-sm text-ink-muted">還沒有人評價這件商品。</p>
    <p class="mt-1 text-xs text-ink-faint">收到貨之後可以到訂單頁分享你的使用心得。</p>
  </div>

  <div v-else class="flex flex-col gap-6 sm:flex-row sm:items-center sm:gap-8">
    <!-- 分數。刻意做大：它是這一區唯一需要「一眼看到」的東西 -->
    <div class="flex shrink-0 flex-col items-center gap-1 sm:w-32">
      <p class="figure text-5xl font-bold leading-none tracking-tight">
        {{ rating.average.toFixed(1) }}
      </p>
      <StarRating :value="rating.average" size="md" class="mt-1.5" />
      <p class="text-xs text-ink-muted">
        <span class="figure">{{ rating.count.toLocaleString() }}</span> 則評價
      </p>
    </div>

    <!-- 分佈。整列可讀，不只是裝飾 -->
    <ul class="flex min-w-0 flex-1 flex-col gap-1.5">
      <li
        v-for="bucket in rating.distribution"
        :key="bucket.stars"
        class="flex items-center gap-3 text-xs"
      >
        <span class="figure w-8 shrink-0 text-right text-ink-muted">
          {{ bucket.stars }} 星
        </span>
        <span
          class="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-surface-sunken"
          role="img"
          :aria-label="`${bucket.stars} 星：${bucket.count} 則，佔 ${bucket.percentage}%`"
        >
          <!--
            寬度 0 時仍保留軌道，讓五條長條的左右端點對齊。
            少了軌道，沒有人給過的星等那一列會整條消失，
            而那看起來像是渲染壞了
          -->
          <span
            class="block h-full rounded-full transition-[width] duration-500"
            :style="{ width: `${bucket.percentage}%`, background: 'var(--star)' }"
          />
        </span>
        <span class="figure w-10 shrink-0 text-right tabular-nums text-ink-faint">
          {{ bucket.count }}
        </span>
      </li>
    </ul>
  </div>
</template>
