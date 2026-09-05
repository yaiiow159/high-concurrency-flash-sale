<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import type { ProductRatingView, ProductView } from '~/types/api'

/**
 * 一列商品，帶標題與「看更多」。
 *
 * <p>首頁的「熱銷排行」「最新上架」與商品頁的「同類商品」都是這個形狀。
 * 分開寫三份的話，間距與卡片大小會慢慢各走各的，
 * 而使用者看到的是同一個網站前後不一致。
 *
 * @param ranked 顯示名次。只有排行榜要——「最新上架」標 1234 沒有意義
 */
withDefaults(defineProps<{
  title: string
  products: ProductView[]
  eyebrow?: string
  description?: string
  ratings?: Record<number, ProductRatingView>
  images?: Record<number, { listUrl: string }>
  ranked?: boolean
  moreTo?: RouteLocationRaw | null
}>(), { eyebrow: undefined, description: undefined, ranked: false, moreTo: null })
</script>

<template>
  <section>
    <div class="mb-4 flex items-end justify-between gap-4">
      <div>
        <p v-if="eyebrow" class="eyebrow mb-1">{{ eyebrow }}</p>
        <h2 class="text-xl font-bold tracking-tight sm:text-2xl">{{ title }}</h2>
        <p v-if="description" class="mt-1 text-xs text-ink-faint">{{ description }}</p>
      </div>
      <NuxtLink
        v-if="moreTo"
        :to="moreTo"
        class="shrink-0 whitespace-nowrap text-sm text-accent hover:underline"
      >
        看更多 →
      </NuxtLink>
    </div>

    <ul class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4">
      <li v-for="(product, index) in products" :key="product.productId">
        <ProductCard
          :product="product"
          :rating="ratings?.[product.productId] ?? null"
          :image-url="images?.[product.productId]?.listUrl ?? null"
          :rank="ranked ? index + 1 : null"
        />
      </li>
    </ul>
  </section>
</template>
