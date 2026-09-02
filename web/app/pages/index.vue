<script setup lang="ts">
import type { ActivityView, ApiResponse } from '~/types/api'

/**
 * 活動列表。
 *
 * ISR 快取 60 秒——活動配置變動慢，而這一頁在開賣前會被反覆重整。
 * 庫存數字在這裡只是參考值，真正的即時數字在活動頁由客戶端取得。
 */
const { data } = await useFetch<ApiResponse<ActivityView[]>>('/api/v1/activities')
const activities = computed(() => data.value?.data ?? [])

useHead({ title: '限時搶購' })
</script>

<template>
  <div>
    <PageHeader
      eyebrow="Flash Sale"
      title="限時搶購"
      description="庫存數字為列表快取值，實際餘量以活動頁為準。"
    />

    <ul
      v-if="activities.length > 0"
      class="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-3 xl:grid-cols-4"
    >
      <li v-for="activity in activities" :key="activity.activityId">
        <NuxtLink :to="`/seckill/${activity.activityId}`" class="group block h-full">
          <AppCard interactive class="flex h-full flex-col overflow-hidden">
            <div class="relative">
              <ProductTile
                :seed="activity.skuId" :label="activity.productName"
                class="transition-transform duration-300 group-hover:scale-[1.03]"
              />
              <!-- 售罄是這一頁最需要一眼看到的事，蓋在視覺上而不是藏在文字裡 -->
              <div
                v-if="activity.availableStock <= 0"
                class="absolute inset-0 flex items-center justify-center bg-black/55"
              >
                <span class="rounded-sm bg-white/95 px-3 py-1 text-sm font-semibold text-danger">
                  已售罄
                </span>
              </div>
            </div>
            <div class="flex flex-1 flex-col justify-between gap-3 p-3.5 sm:p-4">
              <div>
                <h2 class="text-sm font-medium leading-snug sm:text-base">
                  {{ activity.productName }}
                </h2>
                <p class="mt-1 text-xs text-ink-faint">
                  每人限購 <span class="figure">{{ activity.perUserLimit }}</span> 件
                </p>
              </div>
              <!--
                窄卡片上價格與餘量並排會把「餘 996」擠到換行，
                數字被拆成兩行比不顯示更糟。改成上下堆疊，寬螢幕才並排。
              -->
              <div class="flex flex-col gap-0.5 sm:flex-row sm:items-end sm:justify-between sm:gap-2">
                <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
                <span class="figure whitespace-nowrap text-xs text-ink-muted">
                  餘 {{ activity.availableStock }}
                </span>
              </div>
            </div>
          </AppCard>
        </NuxtLink>
      </li>
    </ul>

    <EmptyState v-else title="目前沒有進行中的活動。" hint="先去逛逛一般商品。">
      <AppButton variant="secondary" size="sm" @click="navigateTo('/products')">
        全部商品
      </AppButton>
    </EmptyState>
  </div>
</template>
