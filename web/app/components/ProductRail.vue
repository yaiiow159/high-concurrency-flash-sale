<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import type { ProductRatingView, ProductView } from '~/types/api'

/** 一列商品，帶標題與「更多」。 */
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
    <SectionHeading
      :title="title" :eyebrow="eyebrow" :description="description" :more-to="moreTo"
    />
    <ul class="grid grid-cols-2 gap-3 sm:grid-cols-3 sm:gap-4 lg:grid-cols-4 xl:grid-cols-5">
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
