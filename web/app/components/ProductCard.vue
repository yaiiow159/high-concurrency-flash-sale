<script setup lang="ts">
import type { ProductRatingView, ProductView } from '~/types/api'

/**
 * 商品卡。
 *
 * <p>抽成元件是因為它出現在<b>三個地方</b>：全部商品、首頁的熱銷與最新、
 * 商品頁底下的同類推薦。先前只有一處，複製到第二處的當下就該抽——
 * 三份各自演化的話，同一個商品在不同頁面會長得不一樣，
 * 而那會讓人以為是兩件不同的東西。
 *
 * @param rank 排行榜名次；不傳就不顯示。只有熱銷榜會用到
 */
const props = withDefaults(defineProps<{
  product: ProductView
  rating?: ProductRatingView | null
  rank?: number | null
  /** 主圖網址；沒有時退回確定性色塊（ADR-0027） */
  imageUrl?: string | null
}>(), { rating: null, rank: null, imageUrl: null })

/** 前三名才給顏色。第 4 名開始用一般樣式——不然整排都是重點就沒有重點。 */
const rankTone = computed(() => {
  if (props.rank === null || props.rank > 3) {
    return 'bg-surface/95 text-ink-muted'
  }
  return 'bg-accent text-on-accent'
})
</script>

<template>
  <NuxtLink :to="`/products/${product.productId}`" class="group block h-full">
    <AppCard interactive class="flex h-full flex-col overflow-hidden">
      <div class="relative">
        <!-- 目錄沒有圖片欄位，用 productId 推導的確定性色塊給網格視覺重量 -->
        <ProductTile
          :seed="product.productId" :label="product.name" :src="imageUrl"
          class="transition-transform duration-300 group-hover:scale-[1.03]"
        />
        <span
          v-if="rank !== null"
          class="figure absolute left-2 top-2 rounded-sm px-1.5 py-0.5
                 text-xs font-semibold shadow-rest"
          :class="rankTone"
        >
          {{ rank }}
        </span>
      </div>

      <div class="flex flex-1 flex-col justify-between gap-3 p-3.5 sm:p-4">
        <div>
          <p v-if="product.brand" class="eyebrow mb-1">{{ product.brand }}</p>
          <h3 class="text-sm font-medium leading-snug sm:text-base">{{ product.name }}</h3>

          <!--
            沒有評價的商品**不顯示空星星**，整行留白。
            一排灰星星讀起來像「被評了 0 分」，
            而「還沒有人評價」與「評價很差」是完全不同的兩件事
          -->
          <p v-if="rating?.count" class="mt-1.5 flex items-center gap-1.5">
            <StarRating :value="rating.average" size="sm" />
            <span class="figure text-xs text-ink-faint">
              {{ rating.count.toLocaleString() }}
            </span>
          </p>
        </div>
        <div class="flex items-baseline gap-1">
          <MoneyText :amount="product.lowestPrice" size="lg" />
          <!-- 多規格商品各 SKU 價格不同，列表只能顯示「起」價 -->
          <span class="text-xs text-ink-faint">起</span>
        </div>
      </div>
    </AppCard>
  </NuxtLink>
</template>
