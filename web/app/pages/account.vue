<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useMembership } from '~/composables/useMembership'
import { useAuthStore } from '~/stores/auth'
import type {
  CouponView, MemberProfileView, OrderView, ProductImageView, ProductRatingView, ProductView,
} from '~/types/api'

/**
 * 帳戶總覽。使用者進來要在一屏之內回答「我有什麼事要做」：
 * 幾張單等我付款、幾件貨在路上、有幾張券快過期。
 * 每一格都是一個入口，數字本身不是重點——它們是「值得點進去看嗎」的提示。
 */
const auth = useAuthStore()
const { request } = useApi()
const { profile } = useMembership()
const { loadStatus } = useWishlist()

const member = ref<MemberProfileView | null>(null)
const orderCounts = ref<Record<string, number>>({})
const coupons = ref<CouponView[]>([])
const wishlistTotal = ref<number | null>(null)
const unread = ref<number | null>(null)
const recentOrders = ref<OrderView[]>([])
const recentlyViewed = ref<ProductView[]>([])
const ratings = ref<Record<number, ProductRatingView>>({})
const images = ref<Record<number, ProductImageView>>({})
const loading = ref(true)
const error = ref<string | null>(null)

/** 訂單狀態格。順序就是使用者要處理的順序：先付錢、再等貨、再收貨。 */
const ORDER_TILES = [
  { status: 'PENDING_PAYMENT', label: '待付款', hint: '逾時會自動取消' },
  { status: 'PAID', label: '待出貨', hint: '商家備貨中' },
  { status: 'SHIPPED', label: '待收貨', hint: '貨在路上' },
  { status: 'COMPLETED', label: '已完成', hint: '可評價、可退貨' },
] as const

const authed = { authenticated: true } as const

/**
 * 七個查詢並行。它們互不相依，串起來只是把延遲加成七倍；
 * 各自 catch——通知數掛掉不該讓訂單格也一起消失。
 */
async function load() {
  if (!auth.isAuthenticated) {
    loading.value = false
    return
  }
  loading.value = true
  error.value = null
  const [me, counts, mine, wish, unreadCount, orders, viewed] = await Promise.all([
    profile().catch(() => null),
    request<Record<string, number>>('/api/v1/orders/summary', authed).catch(() => ({})),
    request<CouponView[]>('/api/v1/coupons', authed).catch(() => []),
    request<{ total: number }>('/api/v1/wishlist?page=0&size=1', authed)
      .then((page) => page.total).catch(() => null),
    request<{ count: number }>('/api/v1/notifications/unread-count', authed)
      .then((result) => result.count).catch(() => null),
    request<OrderView[]>('/api/v1/orders?page=0&size=3', authed).catch(() => []),
    request<ProductView[]>('/api/v1/products/recently-viewed?limit=5', authed).catch(() => []),
  ])
  member.value = me
  orderCounts.value = counts
  coupons.value = mine
  wishlistTotal.value = wish
  unread.value = unreadCount
  recentOrders.value = orders
  recentlyViewed.value = viewed
  loading.value = false
  void decorate(viewed.map((product) => product.productId))
}

/** 圖片與評分另外取：它們是裝飾，晚一拍出現沒關係，卡住主要內容才有關係。 */
async function decorate(ids: number[]) {
  if (ids.length === 0) {
    return
  }
  try {
    const query = ids.join(',')
    const [rating, image] = await Promise.all([
      request<Record<number, ProductRatingView>>(`/api/v1/catalog/products/ratings?productIds=${query}`),
      request<Record<number, ProductImageView>>(`/api/v1/catalog/products/images?productIds=${query}`),
    ])
    ratings.value = rating
    images.value = image
    await loadStatus(ids)
  } catch (cause) {
    console.warn(errorMessage(cause, '圖片與評分載入失敗'))
  }
}

/** 七天內到期的券數。「有 5 張券」不會讓人行動，「有 2 張快過期」會。 */
const expiringSoon = computed(() => {
  const limit = Date.now() + 7 * 24 * 60 * 60 * 1000
  return coupons.value.filter((coupon) => new Date(coupon.expiresAt).getTime() <= limit).length
})

const displayName = computed(() => auth.userEmail?.split('@')[0] ?? '會員')

function formatDate(value: string | null): string {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleDateString('zh-TW', { month: '2-digit', day: '2-digit' })
}

onMounted(load)
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    void load()
  }
})

const { seo } = useSeo()
seo({ title: '我的帳戶', noindex: true })
</script>

<template>
  <div class="flex flex-col gap-8">
    <AuthPanel v-if="!auth.isAuthenticated" class="max-w-prose" />

    <template v-else>
      <!-- 第一屏：等級卡 + 訂單狀態格。左邊是「我是誰」，右邊是「我有什麼事」 -->
      <div class="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <div class="flex flex-col gap-3">
          <div>
            <p class="eyebrow mb-1">Account</p>
            <h1 class="text-2xl font-bold tracking-tight">哈囉，{{ displayName }}</h1>
          </div>
          <MemberTierCard :profile="member" :loading="loading" />
          <NuxtLink
            to="/member"
            class="text-sm text-accent underline-offset-4 hover:underline"
          >
            積分紀錄與兌換 →
          </NuxtLink>
        </div>

        <section aria-labelledby="orders-heading">
          <div class="mb-3 flex items-baseline justify-between">
            <h2 id="orders-heading" class="eyebrow">我的訂單</h2>
            <NuxtLink to="/orders" class="text-xs text-ink-muted hover:text-accent">全部訂單 →</NuxtLink>
          </div>
          <ul class="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <li v-for="tile in ORDER_TILES" :key="tile.status">
              <NuxtLink :to="{ path: '/orders', query: { status: tile.status } }" class="block h-full">
                <AppCard
                  interactive
                  class="flex h-full flex-col p-4"
                  :highlighted="(orderCounts[tile.status] ?? 0) > 0 && tile.status === 'PENDING_PAYMENT'"
                >
                  <span class="text-xs text-ink-muted">{{ tile.label }}</span>
                  <span
                    class="figure mt-1 text-3xl font-bold leading-none"
                    :class="(orderCounts[tile.status] ?? 0) > 0 ? 'text-ink' : 'text-ink-faint'"
                  >
                    <SkeletonBlock v-if="loading" class="h-8 w-10" />
                    <template v-else>{{ orderCounts[tile.status] ?? 0 }}</template>
                  </span>
                  <span class="mt-2 text-[11px] text-ink-faint">{{ tile.hint }}</span>
                </AppCard>
              </NuxtLink>
            </li>
          </ul>
        </section>
      </div>

      <p v-if="error" class="rounded-sm bg-danger-soft px-3 py-2 text-sm text-danger">{{ error }}</p>

      <!-- 資產格：券、收藏、通知、瀏覽紀錄。每一格都是入口 -->
      <section aria-label="我的資產">
        <ul class="grid grid-cols-2 gap-3 md:grid-cols-4">
          <li>
            <NuxtLink to="/coupons" class="block h-full">
              <AppCard interactive class="flex h-full items-center gap-3 p-4">
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-accent-soft text-accent">
                  <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M4 8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-2a2 2 0 0 0 0-4z" stroke-linejoin="round" />
                    <path d="M12 8v8" stroke-dasharray="2 2" />
                  </svg>
                </span>
                <span class="min-w-0">
                  <span class="block text-xs text-ink-muted">優惠券</span>
                  <span class="figure block text-lg font-semibold">{{ loading ? '—' : coupons.length }}<span class="ml-0.5 text-xs font-normal text-ink-muted">張</span></span>
                  <span v-if="expiringSoon > 0" class="block text-[11px] text-accent">{{ expiringSoon }} 張七天內到期</span>
                </span>
              </AppCard>
            </NuxtLink>
          </li>
          <li>
            <NuxtLink to="/wishlist" class="block h-full">
              <AppCard interactive class="flex h-full items-center gap-3 p-4">
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-accent-soft text-accent">
                  <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M12 21s-7.5-4.6-9.5-9A5.2 5.2 0 0 1 12 6.5 5.2 5.2 0 0 1 21.5 12c-2 4.4-9.5 9-9.5 9Z" stroke-linejoin="round" />
                  </svg>
                </span>
                <span class="min-w-0">
                  <span class="block text-xs text-ink-muted">收藏</span>
                  <span class="figure block text-lg font-semibold">{{ wishlistTotal ?? '—' }}<span class="ml-0.5 text-xs font-normal text-ink-muted">件</span></span>
                </span>
              </AppCard>
            </NuxtLink>
          </li>
          <li>
            <NuxtLink to="/notifications" class="block h-full">
              <AppCard interactive class="flex h-full items-center gap-3 p-4">
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-sunken text-ink-muted">
                  <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                    <path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16Z" stroke-linejoin="round" />
                    <path d="M10 20a2 2 0 0 0 4 0" stroke-linecap="round" />
                  </svg>
                </span>
                <span class="min-w-0">
                  <span class="block text-xs text-ink-muted">通知</span>
                  <span class="figure block text-lg font-semibold" :class="(unread ?? 0) > 0 ? 'text-accent' : ''">
                    {{ unread ?? '—' }}<span class="ml-0.5 text-xs font-normal text-ink-muted">未讀</span>
                  </span>
                </span>
              </AppCard>
            </NuxtLink>
          </li>
          <li>
            <NuxtLink to="/history" class="block h-full">
              <AppCard interactive class="flex h-full items-center gap-3 p-4">
                <span class="grid h-10 w-10 shrink-0 place-items-center rounded-sm bg-sunken text-ink-muted">
                  <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="1.8">
                    <circle cx="12" cy="12" r="8" />
                    <path d="M12 8v4l2.5 2.5" stroke-linecap="round" />
                  </svg>
                </span>
                <span class="min-w-0">
                  <span class="block text-xs text-ink-muted">瀏覽紀錄</span>
                  <span class="block text-sm font-medium">最近看過的商品</span>
                </span>
              </AppCard>
            </NuxtLink>
          </li>
        </ul>
      </section>

      <!-- 最近訂單：三筆就夠。要看更多的人會去訂單頁，這裡只是提醒「上次買的東西到了沒」 -->
      <section v-if="recentOrders.length > 0" aria-labelledby="recent-orders-heading">
        <h2 id="recent-orders-heading" class="eyebrow mb-3">最近訂單</h2>
        <ul class="flex flex-col gap-2">
          <li v-for="order in recentOrders" :key="order.orderNo">
            <NuxtLink :to="`/orders/${order.orderNo}`" class="block">
              <AppCard interactive class="flex items-center gap-4 px-4 py-3">
                <div class="min-w-0 flex-1">
                  <p class="truncate text-sm font-medium">
                    {{ order.lines[0]?.skuSnapshot ?? '訂單' }}
                    <span v-if="order.lines.length > 1" class="text-ink-muted">等 {{ order.lines.length }} 項</span>
                  </p>
                  <p class="figure mt-0.5 text-xs text-ink-faint">{{ formatDate(order.createdAt) }} · {{ order.orderNo }}</p>
                </div>
                <MoneyText :amount="order.payableAmount ?? 0" class="text-sm" />
                <StatusBadge :status="order.status" />
              </AppCard>
            </NuxtLink>
          </li>
        </ul>
      </section>

      <ProductRail
        v-if="recentlyViewed.length > 0"
        title="最近看過"
        eyebrow="Recently viewed"
        :products="recentlyViewed"
        :ratings="ratings"
        :images="images"
        :more-to="{ path: '/history' }"
      />
    </template>
  </div>
</template>
