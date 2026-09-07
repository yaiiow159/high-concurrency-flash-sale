<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'
import { useNotifications } from '~/composables/useNotifications'
import type { ApiResponse, CategoryView } from '~/types/api'

/**
 * 全站頁首：工具列（帳戶相關）、主列（品牌、搜尋、購物車）、分類列。
 * 工具列不固定，主列與分類列固定——那是使用者一路往下捲時仍需要的東西。
 */
const auth = useAuthStore()
const cart = useCartStore()
const route = useRoute()

const { unreadCount, refreshUnreadCount } = useNotifications()

onMounted(() => {
  if (auth.isAuthenticated) {
    refreshUnreadCount()
  }
})
watch(() => auth.isAuthenticated, (loggedIn) => {
  if (loggedIn) {
    refreshUnreadCount()
  }
})

/** 分類列只取第二層的前幾個；根類目只有一個，第三層太多。 */
const { data: categoryData } = await useFetch<ApiResponse<CategoryView[]>>(
  '/api/v1/catalog/categories')
const categories = computed(() =>
  (categoryData.value?.data ?? []).flatMap((root) => root.children ?? []).slice(0, 8))

const activeCategory = computed(() => {
  const raw = route.query.category
  return typeof raw === 'string' ? Number(raw) : null
})

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}

/** 搜尋。送出後導到搜尋頁；輸入框與網址上的 q 同步，返回時不會清空。 */
const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
watch(() => route.query.q, (q) => {
  keyword.value = typeof q === 'string' ? q : ''
})

function submitSearch() {
  const q = keyword.value.trim()
  navigateTo({ path: '/search', query: q ? { q } : {} })
}

const accountLinks = [
  { to: '/orders', label: '我的訂單' },
  { to: '/reviews', label: '評價' },
  { to: '/returns', label: '退貨' },
  { to: '/addresses', label: '收貨地址' },
  { to: '/coupons', label: '優惠券' },
] as const
</script>

<template>
  <div>
    <!-- 工具列：桌機才有，手機把這些收進會員中心 -->
    <div class="hidden bg-ink-inverse text-white/80 sm:block">
      <div class="mx-auto flex h-8 max-w-content items-center justify-between px-5 text-xs">
        <p class="flex items-center gap-1.5">
          <span class="inline-block h-1.5 w-1.5 rounded-full bg-[#ff6a2c]" aria-hidden="true" />
          限時搶購每日更新 · 展示站，所有交易皆為模擬
        </p>
        <nav class="flex items-center gap-4" aria-label="帳戶">
          <template v-if="auth.isAuthenticated">
            <NuxtLink
              v-for="link in accountLinks" :key="link.to" :to="link.to"
              class="transition-colors hover:text-white"
              :class="isActive(link.to) ? 'text-white' : ''"
            >
              {{ link.label }}
            </NuxtLink>
            <!-- 後台入口只對有權限的人顯示。這不是安全機制，API 仍要 scope -->
            <NuxtLink
              v-if="auth.isAdmin" to="/admin"
              class="font-semibold text-[#ffb08a] transition-colors hover:text-white"
            >
              後台管理
            </NuxtLink>
            <button
              type="button" class="transition-colors hover:text-white"
              @click="auth.logout()"
            >
              登出
            </button>
          </template>
          <template v-else>
            <NuxtLink to="/coupons" class="transition-colors hover:text-white">優惠券</NuxtLink>
            <NuxtLink to="/member" class="font-semibold text-white">登入 / 註冊</NuxtLink>
          </template>
        </nav>
      </div>
    </div>

    <header class="sticky top-0 z-30 bg-surface shadow-[0_1px_0_var(--line),0_2px_8px_rgb(16_18_24/4%)]">
      <!-- 主列 -->
      <div class="mx-auto flex h-16 max-w-content items-center gap-3 px-4 sm:gap-6 sm:px-5">
        <NuxtLink to="/" class="flex shrink-0 items-center gap-2" aria-label="閃購首頁">
          <span
            class="grid h-9 w-9 place-items-center rounded bg-accent text-lg font-extrabold
                   text-white shadow-[0_4px_10px_-4px_rgb(225_29_43/60%)]"
          >
            閃
          </span>
          <span class="hidden flex-col leading-none sm:flex">
            <span class="text-lg font-extrabold tracking-tight text-ink">閃購</span>
            <span class="text-[10px] font-bold tracking-[0.18em] text-accent">FLASH SALE</span>
          </span>
        </NuxtLink>

        <form
          class="flex min-w-0 flex-1 items-center overflow-hidden rounded-sm border-2
                 border-accent bg-surface sm:max-w-2xl"
          role="search"
          @submit.prevent="submitSearch"
        >
          <label for="site-search" class="sr-only">搜尋商品</label>
          <input
            id="site-search"
            v-model="keyword"
            type="search"
            placeholder="搜尋商品、品牌"
            autocomplete="off"
            class="h-10 min-w-0 flex-1 bg-transparent px-3 text-sm placeholder:text-ink-faint
                   focus:outline-none"
          >
          <button
            type="submit"
            class="grid h-10 w-12 shrink-0 place-items-center bg-accent text-white
                   transition-colors hover:bg-accent-hover sm:w-16"
            aria-label="搜尋"
          >
            <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2.2">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" stroke-linecap="round" />
            </svg>
          </button>
        </form>

        <nav class="flex shrink-0 items-center gap-1 sm:gap-2" aria-label="快速入口">
          <NuxtLink
            v-if="auth.isAuthenticated"
            to="/wishlist"
            class="hidden flex-col items-center gap-0.5 rounded-sm px-2 py-1 text-[11px]
                   transition-colors hover:text-accent sm:flex"
            :class="isActive('/wishlist') ? 'text-accent' : 'text-ink-muted'"
          >
            <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
              <path
                d="M12 21s-7.5-4.6-9.5-9A5.2 5.2 0 0 1 12 6.5 5.2 5.2 0 0 1 21.5 12c-2 4.4-9.5 9-9.5 9Z"
                stroke-linejoin="round"
              />
            </svg>
            收藏
          </NuxtLink>

          <NuxtLink
            v-if="auth.isAuthenticated"
            to="/notifications"
            class="relative flex flex-col items-center gap-0.5 rounded-sm px-2 py-1 text-[11px]
                   transition-colors hover:text-accent"
            :class="isActive('/notifications') ? 'text-accent' : 'text-ink-muted'"
          >
            <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15L6 16Z" stroke-linejoin="round" />
              <path d="M10 20a2 2 0 0 0 4 0" stroke-linecap="round" />
            </svg>
            <span class="hidden sm:inline">通知</span>
            <span
              v-if="unreadCount > 0"
              class="figure absolute -top-0.5 right-0 min-w-[1.1rem] rounded-full bg-accent
                     px-1 py-px text-center text-[10px] leading-4 text-white"
            >
              {{ unreadCount > 99 ? '99+' : unreadCount }}
            </span>
          </NuxtLink>

          <NuxtLink
            to="/cart"
            class="relative flex flex-col items-center gap-0.5 rounded-sm px-2 py-1 text-[11px]
                   transition-colors hover:text-accent"
            :class="isActive('/cart') ? 'text-accent' : 'text-ink-muted'"
          >
            <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M3 4h2l2.4 11.2a1.5 1.5 0 0 0 1.5 1.2h8.6a1.5 1.5 0 0 0 1.5-1.1L21 8H6.5" stroke-linecap="round" stroke-linejoin="round" />
              <circle cx="10" cy="20" r="1.3" />
              <circle cx="17" cy="20" r="1.3" />
            </svg>
            <span class="hidden sm:inline">購物車</span>
            <!-- 數量用等寬，否則從 9 變 10 時整條導覽會位移 -->
            <span
              v-if="cart.itemCount > 0"
              class="figure absolute -top-0.5 right-0 min-w-[1.1rem] rounded-full bg-accent
                     px-1 py-px text-center text-[10px] leading-4 text-white"
            >
              {{ cart.itemCount > 99 ? '99+' : cart.itemCount }}
            </span>
          </NuxtLink>

          <NuxtLink
            to="/member"
            class="flex flex-col items-center gap-0.5 rounded-sm px-2 py-1 text-[11px]
                   transition-colors hover:text-accent"
            :class="isActive('/member') ? 'text-accent' : 'text-ink-muted'"
          >
            <svg viewBox="0 0 24 24" class="h-6 w-6" fill="none" stroke="currentColor" stroke-width="1.8">
              <circle cx="12" cy="8.5" r="3.5" />
              <path d="M5 20a7 7 0 0 1 14 0" stroke-linecap="round" />
            </svg>
            <span class="hidden sm:inline">{{ auth.isAuthenticated ? '會員' : '登入' }}</span>
          </NuxtLink>
        </nav>
      </div>

      <!-- 分類列：可橫向捲動，手機上不換行也不截斷 -->
      <nav class="border-t border-line" aria-label="主導覽">
        <div
          class="scroll-hide mx-auto flex h-11 max-w-content items-center gap-1 overflow-x-auto
                 whitespace-nowrap px-4 text-sm sm:px-5"
        >
          <NuxtLink
            to="/"
            class="flex items-center gap-1 rounded-sm px-3 py-1.5 font-bold transition-colors"
            :class="isActive('/') ? 'bg-accent-soft text-accent' : 'text-accent hover:bg-accent-soft'"
          >
            <svg viewBox="0 0 24 24" class="h-4 w-4" fill="currentColor" aria-hidden="true">
              <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8Z" />
            </svg>
            限時搶購
          </NuxtLink>
          <NuxtLink
            to="/products"
            class="rounded-sm px-3 py-1.5 font-medium transition-colors"
            :class="isActive('/products') && activeCategory === null
              ? 'text-accent' : 'text-ink hover:text-accent'"
          >
            全部商品
          </NuxtLink>
          <span class="mx-1 h-4 w-px bg-line" aria-hidden="true" />
          <NuxtLink
            v-for="category in categories"
            :key="category.categoryId"
            :to="{ path: '/products', query: { category: category.categoryId } }"
            class="rounded-sm px-3 py-1.5 transition-colors"
            :class="activeCategory === category.categoryId
              ? 'font-medium text-accent' : 'text-ink-muted hover:text-accent'"
          >
            {{ category.name }}
          </NuxtLink>
          <NuxtLink
            to="/coupons"
            class="ml-auto rounded-sm px-3 py-1.5 font-medium text-ink-muted transition-colors
                   hover:text-accent"
            :class="isActive('/coupons') ? 'text-accent' : ''"
          >
            領券中心
          </NuxtLink>
        </div>
      </nav>
    </header>
  </div>
</template>
