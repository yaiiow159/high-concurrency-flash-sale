<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

/**
 * 營運後台的版型：深色側欄 + 淺色內容區。
 * 與商店長得不一樣是刻意的——後台改的是正式資料，走錯地方的成本是真實資料。
 */
const auth = useAuthStore()
const route = useRoute()

interface NavItem {
  to: string
  label: string
  icon: string
  /** 只在路徑完全相同時算作用中。`/admin` 需要它——否則每一頁都會把總覽點亮 */
  exact?: boolean
}

const SECTIONS: Array<{ title: string, items: NavItem[] }> = [
  {
    title: '營運',
    items: [
      { to: '/admin', label: '總覽', exact: true, icon: 'M4 5h7v7H4zM13 5h7v4h-7zM13 11h7v8h-7zM4 14h7v5H4z' },
      { to: '/admin/orders', label: '訂單管理', icon: 'M6 3h9l4 4v14H6zM15 3v4h4M9 12h6M9 16h6' },
      { to: '/admin/members', label: '會員管理', icon: 'M9 11a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7zM3 20a6 6 0 0 1 12 0M16 4.5a3 3 0 0 1 0 6M21 20a5 5 0 0 0-4-5' },
      { to: '/admin/shipments', label: '出貨處理', icon: 'M3 7h11v9H3zM14 10h4l3 3v3h-7zM7 19a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM17 19a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z' },
      { to: '/admin/returns', label: '退貨審核', icon: 'M9 14 4 9l5-5M4 9h10a6 6 0 0 1 0 12h-3' },
      { to: '/admin/questions', label: '問答管理', icon: 'M4 5h16v11H9l-5 4zM12 8v3M12 13h.01' },
    ],
  },
  {
    title: '商品',
    items: [
      { to: '/admin/products', label: '商品管理', icon: 'M12 3 4 7v10l8 4 8-4V7zM4 7l8 4 8-4M12 11v10' },
      { to: '/admin/activities', label: '秒殺活動', icon: 'M13 2 4 14h6l-1 8 9-12h-6z' },
      { to: '/admin/promotions', label: '優惠管理', icon: 'M4 12l8-8 8 8-8 8zM9 9h.01M15 15h.01M9 15l6-6' },
      { to: '/admin/home', label: '首頁版型', icon: 'M4 5h16v5H4zM4 13h7v6H4zM13 13h7v6h-7z' },
    ],
  },
  {
    title: '系統',
    items: [
      { to: '/admin/reports', label: '銷售報表', icon: 'M4 19h16M7 16V9M12 16V5M17 16v-6' },
      { to: '/admin/ops', label: '維運工具', icon: 'M14.5 4.5a4 4 0 0 0-5 5L4 15v5h5l5.5-5.5a4 4 0 0 0 5-5l-3 3-2.5-2.5z' },
    ],
  },
]

function isActive(to: string, exact = false): boolean {
  return exact ? route.path === to : route.path.startsWith(to)
}

/** 頂列顯示目前頁名：從導覽項目反查，找不到就退回「後台」。 */
const currentLabel = computed(() =>
  SECTIONS.flatMap((section) => section.items)
    .find((item) => isActive(item.to, item.exact))?.label ?? '後台')
</script>

<template>
  <div class="admin-shell flex min-h-screen bg-ground">
    <!-- lg 以下收成頂部橫列：「出貨」這件事有人會拿著手機在倉庫裡做 -->
    <aside
      class="admin-side flex shrink-0 flex-col
             max-lg:w-full max-lg:flex-row max-lg:items-center max-lg:gap-3 max-lg:overflow-x-auto
             max-lg:px-3 max-lg:py-2
             lg:sticky lg:top-0 lg:h-screen lg:w-60 lg:px-3 lg:py-5"
    >
      <NuxtLink to="/admin" class="flex shrink-0 items-center gap-2.5 px-2 lg:mb-6">
        <span
          class="grid h-8 w-8 place-items-center rounded-sm bg-accent text-sm font-extrabold
                 text-white"
        >
          閃
        </span>
        <span class="flex flex-col leading-none">
          <span class="text-sm font-extrabold tracking-tight text-white">閃購後台</span>
          <span class="mt-0.5 text-[10px] font-semibold uppercase tracking-[0.16em] text-white/45">
            Console
          </span>
        </span>
      </NuxtLink>

      <nav
        class="scroll-hide flex gap-3 max-lg:items-center max-lg:overflow-x-auto lg:flex-1 lg:flex-col lg:gap-5"
        aria-label="後台導覽"
      >
        <div
          v-for="section in SECTIONS"
          :key="section.title"
          class="flex gap-0.5 max-lg:items-center lg:flex-col"
        >
          <p class="eyebrow mb-1 px-3 max-lg:hidden">{{ section.title }}</p>
          <NuxtLink
            v-for="item in section.items"
            :key="item.to"
            :to="item.to"
            class="admin-link flex items-center gap-2.5 whitespace-nowrap rounded-sm px-3 py-2
                   text-sm transition-colors"
            :class="isActive(item.to, item.exact) ? 'is-active' : ''"
          >
            <svg
              viewBox="0 0 24 24" class="h-4 w-4 shrink-0 max-lg:hidden" fill="none"
              stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"
              aria-hidden="true"
            >
              <path :d="item.icon" />
            </svg>
            {{ item.label }}
          </NuxtLink>
        </div>
      </nav>

      <div class="flex shrink-0 items-center gap-1 max-lg:ml-auto lg:mt-4 lg:flex-col lg:items-stretch lg:border-t lg:border-white/10 lg:pt-4">
        <!-- 回商店必須顯眼：後台與商店是同一個應用，走錯地方的成本是真實資料 -->
        <NuxtLink
          to="/"
          class="admin-link flex items-center gap-2 whitespace-nowrap rounded-sm px-3 py-2 text-sm
                 transition-colors"
        >
          <svg viewBox="0 0 24 24" class="h-4 w-4 max-lg:hidden" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M3 10 12 3l9 7v10H3zM10 20v-6h4v6" />
          </svg>
          回商店
        </NuxtLink>
        <button
          type="button"
          class="admin-link flex items-center gap-2 whitespace-nowrap rounded-sm px-3 py-2
                 text-left text-sm transition-colors"
          @click="auth.logout()"
        >
          <svg viewBox="0 0 24 24" class="h-4 w-4 max-lg:hidden" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M10 4H5v16h5M14 8l4 4-4 4M18 12H9" />
          </svg>
          登出
        </button>
      </div>
    </aside>

    <div class="flex min-w-0 flex-1 flex-col">
      <!-- 頂列：目前位置與身分。後台沒有搜尋列，這裡只放路標 -->
      <div class="flex h-12 items-center justify-between border-b border-line bg-surface px-5 sm:px-8">
        <p class="text-sm text-ink-muted">
          <span class="text-ink-faint">後台</span>
          <span class="mx-1.5 text-ink-faint">/</span>
          <span class="font-semibold text-ink">{{ currentLabel }}</span>
        </p>
        <p class="hidden items-center gap-2 text-xs text-ink-muted sm:flex">
          <span class="rounded-full bg-accent-soft px-2 py-0.5 font-semibold text-accent">
            管理員
          </span>
          {{ auth.userEmail }}
        </p>
      </div>

      <main class="flex-1 px-5 py-6 sm:px-8 sm:py-8">
        <div class="mx-auto max-w-6xl">
          <slot />
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/*
 * 後台自己的一組顏色，不套商店的 token：讓兩者長得像只會讓人
 * 在按下「釋放庫存」之後才發現自己以為還在逛商店。
 * 側欄固定深色，是一個「你在後台」的恆定訊號。
 */
.admin-side {
  background: #171a24;
  color: #c7cbd6;
}

.admin-link {
  color: #9aa0b0;
}

.admin-link:hover {
  background: rgb(255 255 255 / 6%);
  color: #f3f4f8;
}

.admin-side .eyebrow {
  color: #5f6577;
}

.admin-link.is-active {
  background: rgb(225 29 43 / 16%);
  color: #ff8a80;
  font-weight: 600;
}
</style>
