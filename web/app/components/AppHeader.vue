<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'
import { useCartStore } from '~/stores/cart'
import { useNotifications } from '~/composables/useNotifications'

/**
 * 全站頁首。
 *
 * 先前每一頁自己在標題旁邊放幾個連結，於是「購物車」在商品頁有、
 * 在訂單頁沒有，使用者得先按上一頁才找得到。導覽是全站的事，
 * 不該由每一頁各自決定。
 *
 * 購物車數字未登入時取自 localStorage、登入後取自伺服器——
 * 兩者都由 store 統一計算，這裡只負責顯示。
 */
const auth = useAuthStore()
const cart = useCartStore()

/**
 * 未讀數。
 *
 * <p>只在<b>登入狀態變成 true</b> 時取一次，不做輪詢。
 * 通知不是即時通訊——為了讓紅點早幾秒出現而每 10 秒打一次，
 * 是拿伺服器成本換一個沒有人在等的更新。
 *
 * <p>使用者實際開啟通知頁時那一頁自己會重取，紅點也會跟著更新。
 */
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
const route = useRoute()

const links = [
  { to: '/', label: '限時搶購' },
  { to: '/products', label: '全部商品' },
  { to: '/search', label: '搜尋' },
]

function isActive(to: string): boolean {
  return to === '/' ? route.path === '/' : route.path.startsWith(to)
}
</script>

<template>
  <header class="sticky top-0 z-20 border-b border-line bg-surface/85 backdrop-blur">
    <!--
      whitespace-nowrap 不可省：中文可以在任兩個字之間斷行，
      少了它「限時搶購」在窄螢幕上會被拆成兩行，整個頁首變成三層高。
    -->
    <div class="mx-auto flex max-w-content items-center gap-3 whitespace-nowrap px-4 py-3 sm:gap-6 sm:px-5">
      <NuxtLink to="/" class="flex items-baseline gap-2 font-semibold tracking-tight">
        <span class="text-accent">閃購</span>
        <span class="hidden text-xs font-normal text-ink-faint sm:inline">FLASH SALE</span>
      </NuxtLink>

      <nav class="flex items-center gap-0.5 text-[13px] sm:gap-1 sm:text-sm" aria-label="主導覽">
        <NuxtLink
          v-for="link in links"
          :key="link.to"
          :to="link.to"
          class="rounded-sm px-2 py-1.5 transition-colors sm:px-2.5"
          :class="isActive(link.to)
            ? 'font-medium text-accent'
            : 'text-ink-muted hover:text-ink'"
        >
          {{ link.label }}
        </NuxtLink>
      </nav>

      <!--
        允許換行。導覽項目加到第六個之後，375px 就裝不下了——
        實測溢出 21px，而頁面本體橫捲是這個專案明確禁止的。
        換行讓手機上的標題列高一點，但沒有任何項目被藏起來；
        把「通知」藏到 sm: 之後才顯示是更糟的取捨，
        因為未讀紅點正是手機上最需要看到的東西。
      -->
      <div class="ml-auto flex flex-wrap items-center justify-end gap-0.5
                  text-[13px] sm:gap-1 sm:text-sm">
        <NuxtLink
          to="/cart"
          class="flex items-center gap-1.5 rounded-sm px-2 py-1.5 transition-colors sm:px-2.5"
          :class="isActive('/cart') ? 'font-medium text-accent' : 'text-ink-muted hover:text-ink'"
        >
          購物車
          <!-- 數量用等寬，否則從 9 變 10 時整條導覽會位移 -->
          <span
            v-if="cart.itemCount > 0"
            class="figure rounded-sm bg-accent px-1.5 py-0.5 text-xs text-on-accent"
          >
            {{ cart.itemCount }}
          </span>
        </NuxtLink>

        <template v-if="auth.isAuthenticated">
          <NuxtLink
            to="/orders"
            class="rounded-sm px-2 py-1.5 text-ink-muted transition-colors hover:text-ink sm:px-2.5"
            :class="isActive('/orders') ? 'font-medium text-accent' : ''"
          >
            訂單
          </NuxtLink>
          <NuxtLink
            to="/notifications"
            class="relative flex items-center gap-1.5 rounded-sm px-2 py-1.5 text-ink-muted
                   transition-colors hover:text-ink sm:px-2.5"
            :class="isActive('/notifications') ? 'font-medium text-accent' : ''"
          >
            通知
            <!-- 未讀數用等寬，否則從 9 變 10 時整條導覽會位移；
                 超過 99 顯示 99+，三位數會把導覽撐開 -->
            <span
              v-if="unreadCount > 0"
              class="figure rounded-sm bg-danger px-1.5 py-0.5 text-xs text-white"
            >
              {{ unreadCount > 99 ? '99+' : unreadCount }}
            </span>
          </NuxtLink>
          <NuxtLink
            to="/reviews"
            class="hidden rounded-sm px-2.5 py-1.5 text-ink-muted transition-colors hover:text-ink sm:block"
            :class="isActive('/reviews') ? 'font-medium text-accent' : ''"
          >
            評價
          </NuxtLink>
          <NuxtLink
            to="/returns"
            class="hidden rounded-sm px-2.5 py-1.5 text-ink-muted transition-colors hover:text-ink sm:block"
            :class="isActive('/returns') ? 'font-medium text-accent' : ''"
          >
            退貨
          </NuxtLink>
          <NuxtLink
            to="/addresses"
            class="hidden rounded-sm px-2.5 py-1.5 text-ink-muted transition-colors hover:text-ink sm:block"
            :class="isActive('/addresses') ? 'font-medium text-accent' : ''"
          >
            收貨地址
          </NuxtLink>
          <button
            type="button"
            class="rounded-sm px-2 py-1.5 text-ink-muted transition-colors hover:text-ink sm:px-2.5"
            @click="auth.logout()"
          >
            登出
          </button>
        </template>
      </div>
    </div>
  </header>
</template>
