<script setup lang="ts">
import type { OrderDiscount } from '~/types/api'

/**
 * 金額明細：小計 → 逐筆折扣 → 應付。
 *
 * **折扣逐筆列出，不是一行「優惠 −2000」。**
 * 使用者問的是「為什麼折了 2000」，而那需要看到是哪幾個優惠。
 * 訂單頁與結帳頁共用這個元件，是為了讓「結帳時看到的」與
 * 「訂單成立後看到的」長得一模一樣——兩邊各寫一份，
 * 遲早會有一邊的四捨五入或排序不同，而使用者只會覺得系統在騙他。
 */
withDefaults(defineProps<{
  subtotal: number | null | undefined
  discounts: OrderDiscount[]
  payable: number | null | undefined
  /** 應付金額的字級。結帳頁要大，訂單詳情頁小一點 */
  size?: 'lg' | 'xl'
}>(), { size: 'lg' })
</script>

<template>
  <div class="flex flex-col gap-2">
    <div
      v-if="discounts.length > 0"
      class="flex items-baseline justify-between text-sm text-ink-muted"
    >
      <span>小計</span>
      <MoneyText :amount="subtotal" size="sm" tone="muted" />
    </div>

    <div
      v-for="discount in discounts"
      :key="`${discount.sourceType}-${discount.sourceId}-${discount.name}`"
      class="flex items-baseline justify-between gap-3 text-sm"
    >
      <span class="truncate text-ink-muted">{{ discount.name }}</span>
      <span class="figure shrink-0 text-accent">
        −<MoneyText :amount="discount.amount" size="sm" tone="accent" />
      </span>
    </div>

    <div
      v-if="discounts.length > 0"
      class="mt-1 flex items-baseline justify-between border-t border-line pt-2"
    >
      <span class="eyebrow">應付</span>
      <MoneyText :amount="payable" :size="size" />
    </div>
    <MoneyText v-else :amount="payable" :size="size" />
  </div>
</template>
