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

    <ul v-if="activities.length > 0" class="grid gap-3 sm:grid-cols-2">
      <li v-for="activity in activities" :key="activity.activityId">
        <NuxtLink :to="`/seckill/${activity.activityId}`" class="block h-full">
          <AppCard interactive class="flex h-full flex-col justify-between gap-6 p-5">
            <div>
              <h2 class="font-semibold leading-snug">{{ activity.productName }}</h2>
              <p class="mt-1.5 text-sm text-ink-muted">
                每人限購 <span class="figure">{{ activity.perUserLimit }}</span> 件
              </p>
            </div>
            <div class="flex items-end justify-between gap-3">
              <MoneyText :amount="activity.seckillPrice" size="lg" tone="danger" />
              <span class="figure text-sm text-ink-muted">
                餘 {{ activity.availableStock }}
              </span>
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
