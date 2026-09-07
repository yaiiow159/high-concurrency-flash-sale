<script setup lang="ts">
import type { ActivityView } from '~/types/api'

/** 限時搶購卡。庫存進度與售罄狀態是這張卡最需要一眼看到的事。 */
const props = defineProps<{ activity: ActivityView }>()

const soldOut = computed(() => props.activity.availableStock <= 0)
</script>

<template>
  <NuxtLink :to="`/seckill/${activity.activityId}`" class="group block h-full">
    <AppCard interactive class="flex h-full flex-col overflow-hidden">
      <div class="relative overflow-hidden bg-sunken">
        <ProductTile
          :seed="activity.skuId" :label="activity.productName"
          class="!rounded-none transition-transform duration-300 group-hover:scale-[1.04]"
        />
        <div
          v-if="soldOut"
          class="absolute inset-0 grid place-items-center bg-white/70 backdrop-blur-[1px]"
        >
          <span
            class="rounded-full border-2 border-ink-faint px-4 py-1.5 text-sm font-extrabold
                   text-ink-muted"
          >
            已售罄
          </span>
        </div>
        <span
          v-else
          class="bg-promo absolute left-0 top-0 rounded-br px-2 py-1 text-[11px] font-bold
                 text-white"
        >
          限時
        </span>
      </div>

      <div class="flex flex-1 flex-col gap-2 p-3">
        <h3 class="line-clamp-2 text-sm leading-snug text-ink group-hover:text-accent">
          {{ activity.productName }}
        </h3>
        <!-- 允許換行：375px 的兩欄卡片放不下「NT$ 29,900 限購 2 件」一整行 -->
        <div class="flex flex-wrap items-baseline justify-between gap-x-2">
          <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
          <span class="whitespace-nowrap text-[11px] text-ink-faint">
            限購 <span class="figure">{{ activity.perUserLimit.toLocaleString() }}</span> 件
          </span>
        </div>
        <StockIndicator
          class="mt-auto"
          :available="activity.availableStock" :total="activity.totalStock" compact
        />
        <span
          class="mt-1 grid h-9 place-items-center rounded-sm text-sm font-bold"
          :class="soldOut ? 'bg-sunken text-ink-faint' : 'btn-promo'"
        >
          {{ soldOut ? '已搶完' : '馬上搶' }}
        </span>
      </div>
    </AppCard>
  </NuxtLink>
</template>
