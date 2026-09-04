<script setup lang="ts">
import type { OrderDiscount } from '~/types/api'

/** 與後端 `DiscountType.SHIPPING` 是一組契約，改了任何一邊另一邊都要跟。 */
const SHIPPING_SOURCE_TYPE = 'SHIPPING'

/**
 * 金額明細：小計 → 逐筆折扣 → 應付。
 *
 * **折扣逐筆列出，不是一行「優惠 −2000」。**
 * 使用者問的是「為什麼折了 2000」，而那需要看到是哪幾個優惠。
 * 訂單頁與結帳頁共用這個元件，是為了讓「結帳時看到的」與
 * 「訂單成立後看到的」長得一模一樣——兩邊各寫一份，
 * 遲早會有一邊的四捨五入或排序不同，而使用者只會覺得系統在騙他。
 */
const props = withDefaults(defineProps<{
  subtotal: number | null | undefined
  discounts: OrderDiscount[]
  /** **商品**折後應付，不含運費 */
  payable: number | null | undefined
  /** 運費。undefined 代表這個情境沒有運費概念（例如秒殺訂單） */
  shippingFee?: number | null
  /**
   * 運費算不算得出來。
   *
   * false 時顯示「選擇地址後計算」而不是「NT$ 0」——
   * 後者會讓使用者以為免運，然後在下一步被多收錢。
   */
  shippingKnown?: boolean
  /** 區域名稱，用來解釋為什麼離島比較貴 */
  shippingZone?: string | null
  /** 應付金額的字級。結帳頁要大，訂單詳情頁小一點 */
  size?: 'lg' | 'xl'
}>(), { size: 'lg', shippingFee: undefined, shippingKnown: true, shippingZone: null })

/** 有運費這個概念就要顯示它——包含「0 元」的免運，那是使用者拿到的好處。 */
const showsShipping = computed(() => props.shippingFee !== undefined
  && props.shippingFee !== null)

/**
 * 免運折抵**不可與商品折扣並排列出**。
 *
 * 它沒有從小計扣，而是從運費扣，而 `shippingFee` 傳進來時已經是實收淨額
 * （後端 `Order.shippingFee` 的語意就是「已扣掉免運折抵的實收運費」）。
 * 兩邊都列的話，同一筆優惠在畫面上出現兩次：
 * 使用者由上往下加會得到 2,997 − 80 = 2,917，而應付寫的是 2,997。
 *
 * 領域層的 `Order` 建構訂單時也是這樣切的（`!discount.appliesToShipping()`
 * 才計入行加總恆等式）——前端跟著同一條線切，兩邊才不會長出不同的解讀。
 */
const goodsDiscounts = computed(() => props.discounts
  .filter(discount => discount.sourceType !== SHIPPING_SOURCE_TYPE))

/** 折抵的名稱要留著——「免運」不說明是哪個活動給的，客服就查不到。 */
const shippingDiscountName = computed(() => props.discounts
  .find(discount => discount.sourceType === SHIPPING_SOURCE_TYPE)?.name ?? null)

/** 總計。運費未知時不加，避免顯示一個之後會變的數字。 */
const total = computed(() => {
  const goods = props.payable ?? 0
  return showsShipping.value && props.shippingKnown
    ? goods + (props.shippingFee ?? 0)
    : props.payable
})

/** 有折扣或有運費就需要展開明細；兩者皆無時一個數字就夠。 */
const detailed = computed(() => props.discounts.length > 0 || showsShipping.value)
</script>

<template>
  <div class="flex flex-col gap-2">
    <div
      v-if="detailed"
      class="flex items-baseline justify-between text-sm text-ink-muted"
    >
      <span>小計</span>
      <MoneyText :amount="subtotal" size="sm" tone="muted" />
    </div>

    <div
      v-for="discount in goodsDiscounts"
      :key="`${discount.sourceType}-${discount.sourceId}-${discount.name}`"
      class="flex items-baseline justify-between gap-3 text-sm"
    >
      <span class="truncate text-ink-muted">{{ discount.name }}</span>
      <span class="figure shrink-0 text-accent">
        −<MoneyText :amount="discount.amount" size="sm" tone="accent" />
      </span>
    </div>

    <!--
      運費放在折扣之後、總計之前。順序就是計算順序，
      而使用者看帳單的方式是由上往下加——順序錯了他就對不起來
    -->
    <div
      v-if="showsShipping"
      class="flex items-baseline justify-between gap-3 text-sm"
    >
      <span class="min-w-0 text-ink-muted">
        運費<span v-if="shippingZone" class="ml-1 text-xs text-ink-faint">
          （{{ shippingZone }}）
        </span>
        <span
          v-if="shippingDiscountName"
          class="ml-1 truncate text-xs text-accent"
        >· {{ shippingDiscountName }}</span>
      </span>
      <span v-if="!shippingKnown" class="text-xs text-ink-faint">選擇地址後計算</span>
      <span v-else-if="shippingFee === 0" class="text-sm font-medium text-accent">免運</span>
      <MoneyText v-else :amount="shippingFee" size="sm" />
    </div>

    <div
      v-if="detailed"
      class="mt-1 flex items-baseline justify-between border-t border-line pt-2"
    >
      <span class="eyebrow">{{ shippingKnown ? '應付' : '商品小計' }}</span>
      <MoneyText :amount="total" :size="size" />
    </div>
    <MoneyText v-else :amount="payable" :size="size" />
  </div>
</template>
