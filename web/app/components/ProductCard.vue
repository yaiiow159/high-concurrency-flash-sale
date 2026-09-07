<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import type { ProductRatingView, ProductView } from '~/types/api'

/** 商品卡。 */
const props = withDefaults(defineProps<{
  product: ProductView
  rating?: ProductRatingView | null
  rank?: number | null
  /** 主圖網址；沒有時退回確定性色塊（ADR-0027） */
  imageUrl?: string | null
  wishlist?: boolean
}>(), { rating: null, rank: null, imageUrl: null, wishlist: true })

const auth = useAuthStore()

/** 前三名才給品牌色，整排都是重點就沒有重點。 */
const rankTone = computed(() => {
  if (props.rank === null || props.rank > 3) {
    return 'bg-ink-inverse/85 text-white'
  }
  return 'bg-promo text-white'
})
</script>

<template>
  <NuxtLink :to="`/products/${product.productId}`" class="group block h-full">
    <AppCard interactive class="flex h-full flex-col overflow-hidden">
      <div class="relative overflow-hidden bg-sunken">
        <ProductTile
          :seed="product.productId" :label="product.name" :src="imageUrl"
          class="!rounded-none transition-transform duration-300 group-hover:scale-[1.04]"
        />
        <span
          v-if="rank !== null"
          class="figure absolute left-0 top-0 rounded-br px-2 py-1 text-xs"
          :class="rankTone"
        >
          {{ rank }}
        </span>
        <!-- 未登入不顯示：按了只會跳「請先登入」，那是一個假的可用按鈕 -->
        <WishlistButton
          v-if="wishlist && auth.isAuthenticated"
          :product-id="product.productId"
          size="sm"
          class="absolute right-2 top-2 shadow-rest"
        />
      </div>

      <div class="flex flex-1 flex-col gap-1 p-3">
        <p v-if="product.brand" class="truncate text-[11px] font-semibold text-ink-faint">
          {{ product.brand }}
        </p>
        <h3
          class="line-clamp-2 text-sm leading-snug text-ink transition-colors
                 group-hover:text-accent"
        >
          {{ product.name }}
        </h3>

        <!-- 沒有評價就整行留白：一排灰星讀起來像「被評了 0 分」 -->
        <p v-if="rating?.count" class="mt-0.5 flex items-center gap-1.5">
          <StarRating :value="rating.average" size="sm" />
          <span class="figure text-xs text-ink-muted">{{ rating.average.toFixed(1) }}</span>
          <span class="text-xs text-ink-faint">({{ rating.count.toLocaleString() }})</span>
        </p>

        <div class="mt-auto flex items-baseline gap-1 pt-1.5">
          <MoneyText :amount="product.lowestPrice" size="lg" tone="danger" />
          <!-- 多規格商品各 SKU 價格不同，列表只能顯示「起」價 -->
          <span class="text-xs text-ink-faint">起</span>
        </div>
      </div>
    </AppCard>
  </NuxtLink>
</template>
