<script setup lang="ts">
import type { RouteLocationRaw } from 'vue-router'
import type { ProductRatingView, ProductView } from '~/types/api'

/** 一列商品，帶標題與「看更多」。 */
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
