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
  <main class="mx-auto max-w-3xl px-5 py-10">
    <header class="flex flex-wrap items-baseline justify-between gap-3">
      <h1 class="text-3xl font-black tracking-tight">限時搶購</h1>
      <NuxtLink to="/products" class="text-sm text-[var(--accent)] hover:underline">
        全部商品 →
      </NuxtLink>
    </header>
    <p class="mt-2 text-sm text-[var(--ink-muted)]">
      庫存數字為列表快取值，實際餘量以活動頁為準
    </p>

    <ul class="mt-6 flex flex-col gap-3">
      <li v-for="activity in activities" :key="activity.activityId">
        <NuxtLink
          :to="`/seckill/${activity.activityId}`"
          class="flex items-center justify-between gap-4 rounded border border-[var(--line)]
                 bg-[var(--surface)] p-5 transition hover:border-[var(--accent)]"
        >
          <div>
            <div class="font-semibold">{{ activity.productName }}</div>
            <div class="mt-1 text-sm text-[var(--ink-muted)]">
              每人限購 {{ activity.perUserLimit }} 件
            </div>
          </div>
          <div class="text-right">
            <div class="font-mono text-xl font-bold text-[var(--danger)]">
              NT$ {{ activity.seckillPrice.toLocaleString() }}
            </div>
            <div class="tabular mt-1 font-mono text-sm text-[var(--ink-muted)]">
              餘 {{ activity.availableStock }}
            </div>
          </div>
        </NuxtLink>
      </li>
    </ul>

    <p v-if="activities.length === 0" class="mt-10 text-[var(--ink-muted)]">
      目前沒有進行中的活動。
    </p>
  </main>
</template>
