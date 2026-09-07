<script setup lang="ts">
import type { ProductImageView, ProductRatingView, RankedProductView } from '~/types/api'

/**
 * 熱銷排行。前三名有獎牌色、其餘是灰底數字——整排都是重點就沒有重點。
 * 第一名做大：排行榜的全部戲劇性都在「誰是第一」，其他名次是佐證。
 */
withDefaults(defineProps<{
  items: RankedProductView[]
  title?: string
  eyebrow?: string
  description?: string
  ratings?: Record<number, ProductRatingView>
  images?: Record<number, ProductImageView>
  moreTo?: string | null
  /** 首頁只放前幾名，完整榜單在 /rankings */
  compact?: boolean
}>(), {
  title: '本週熱銷 TOP 10',
  eyebrow: 'Best sellers',
  description: undefined,
  ratings: () => ({}),
  images: () => ({}),
  moreTo: '/rankings',
  compact: false,
})

const MEDALS: Record<number, string> = {
  1: 'bg-[#d4a548] text-[#2b1d05]',
  2: 'bg-[#9aa7ae] text-[#12191c]',
  3: 'bg-[#b07a4a] text-[#2a1508]',
}

function medal(rank: number): string {
  return MEDALS[rank] ?? 'bg-sunken text-ink-muted'
}
</script>

<template>
  <section :aria-label="title">
    <SectionHeading :title="title" :eyebrow="eyebrow" :description="description" :more-to="moreTo" more-label="完整榜單" />

    <ol
      class="grid gap-3"
      :class="compact ? 'sm:grid-cols-2 lg:grid-cols-5' : 'sm:grid-cols-2 lg:grid-cols-3'"
    >
      <li
        v-for="entry in items"
        :key="entry.product.productId"
        :class="entry.rank === 1 && compact ? 'sm:col-span-2 lg:col-span-1 lg:row-span-2' : ''"
      >
        <NuxtLink :to="`/products/${entry.product.productId}`" class="group block h-full">
          <AppCard interactive class="flex h-full overflow-hidden" :class="entry.rank === 1 && compact ? 'flex-col' : 'items-stretch'">
            <div
              class="relative shrink-0 overflow-hidden bg-sunken"
              :class="entry.rank === 1 && compact ? 'w-full' : 'w-28 sm:w-32'"
            >
              <ProductTile
                :seed="entry.product.productId"
                :label="entry.product.name"
                :src="images[entry.product.productId]?.listUrl ?? null"
                :ratio="entry.rank === 1 && compact ? 'wide' : 'square'"
                class="h-full transition-transform duration-300 group-hover:scale-[1.03]"
              />
              <span
                class="figure absolute left-2 top-2 grid h-7 min-w-7 place-items-center rounded-sm px-1.5 text-xs font-extrabold shadow-rest"
                :class="medal(entry.rank)"
                :aria-label="`第 ${entry.rank} 名`"
              >
                {{ entry.rank }}
              </span>
            </div>
            <div class="flex min-w-0 flex-1 flex-col gap-1 p-3">
              <p v-if="entry.product.brand" class="eyebrow">{{ entry.product.brand }}</p>
              <p class="line-clamp-2 text-sm font-semibold leading-snug group-hover:text-accent">
                {{ entry.product.name }}
              </p>
              <div v-if="ratings[entry.product.productId]?.count" class="flex items-center gap-1.5">
                <StarRating :value="ratings[entry.product.productId].average" size="sm" />
                <span class="figure text-[11px] text-ink-faint">({{ ratings[entry.product.productId].count }})</span>
              </div>
              <div class="mt-auto flex items-end justify-between gap-2 pt-1">
                <MoneyText :amount="entry.product.lowestPrice" :size="entry.rank === 1 && compact ? 'lg' : 'md'" />
                <span class="figure whitespace-nowrap text-[11px] text-ink-faint">
                  售出 {{ entry.soldQuantity.toLocaleString() }} 件
                </span>
              </div>
            </div>
          </AppCard>
        </NuxtLink>
      </li>
    </ol>
  </section>
</template>
