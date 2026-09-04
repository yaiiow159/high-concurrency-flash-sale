<script setup lang="ts">
import { useAuthStore } from '~/stores/auth'

/**
 * 營運後台的版型。
 *
 * **側邊欄而不是頂部導覽**，這是後台與商店最大的視覺差異，也是刻意的：
 * 商店的導覽只有五、六項且使用者是逛的；後台的功能會一直長，
 * 而使用者是「帶著任務來」的——他知道自己要去哪一頁，
 * 側邊欄讓每一項永遠在同一個位置，不必先掃過一列橫向文字。
 *
 * 深色的側欄配淺色的內容區是後台的通用語彙（Shopify、Stripe、Vercel 都這樣），
 * 而它同時解決一個實際問題：一眼就知道「我現在在後台，不是在商店」。
 * 拿商店的殼直接套後台，維運人員會在改到正式資料時才發現自己走錯地方。
 */
const auth = useAuthStore()
const route = useRoute()

interface NavItem {
  to: string
  label: string
  /** 只在路徑完全相同時算作用中。`/admin` 需要它——否則每一頁都會把總覽點亮 */
  exact?: boolean
}

const SECTIONS: Array<{ title: string, items: NavItem[] }> = [
  {
    title: '營運',
    items: [
      { to: '/admin', label: '總覽', exact: true },
      { to: '/admin/shipments', label: '出貨處理' },
      { to: '/admin/returns', label: '退貨審核' },
    ],
  },
  {
    title: '商品',
    items: [
      { to: '/admin/products', label: '商品管理' },
      { to: '/admin/activities', label: '秒殺活動' },
    ],
  },
  {
    title: '系統',
    items: [
      { to: '/admin/ops', label: '維運工具' },
    ],
  },
]

function isActive(to: string, exact = false): boolean {
  return exact ? route.path === to : route.path.startsWith(to)
}
</script>

<template>
  <div class="admin-shell flex min-h-screen">
    <!--
      side nav。lg 以下收成頂部橫列——後台雖然以桌機為主，
      但「出貨」這件事有人會拿著手機在倉庫裡做
    -->
    <aside
      class="admin-side flex shrink-0 flex-col gap-6 border-line px-3 py-4
             max-lg:w-full max-lg:flex-row max-lg:items-center max-lg:gap-4
             max-lg:overflow-x-auto max-lg:border-b
             lg:sticky lg:top-0 lg:h-screen lg:w-56 lg:border-r lg:px-4 lg:py-6"
    >
      <NuxtLink to="/admin" class="flex shrink-0 items-baseline gap-2 px-2">
        <span class="font-semibold tracking-tight">閃購</span>
        <span class="text-[11px] font-medium uppercase tracking-wider opacity-60">Console</span>
      </NuxtLink>

      <nav class="flex gap-4 max-lg:items-center lg:flex-1 lg:flex-col lg:gap-5" aria-label="後台導覽">
        <div
          v-for="section in SECTIONS"
          :key="section.title"
          class="flex gap-1 max-lg:items-center lg:flex-col"
        >
          <p class="eyebrow px-2 max-lg:hidden">{{ section.title }}</p>
          <NuxtLink
            v-for="item in section.items"
            :key="item.to"
            :to="item.to"
            class="admin-link whitespace-nowrap rounded-sm px-2.5 py-1.5 text-sm transition-colors"
            :class="isActive(item.to, item.exact) ? 'is-active' : ''"
          >
            {{ item.label }}
          </NuxtLink>
        </div>
      </nav>

      <div class="flex shrink-0 items-center gap-1 max-lg:ml-auto lg:flex-col lg:items-stretch">
        <!-- 回商店必須顯眼：後台與商店是同一個應用，走錯地方的成本是真實資料 -->
        <NuxtLink
          to="/"
          class="admin-link whitespace-nowrap rounded-sm px-2.5 py-1.5 text-sm transition-colors"
        >
          ← 回商店
        </NuxtLink>
        <button
          type="button"
          class="admin-link whitespace-nowrap rounded-sm px-2.5 py-1.5 text-left text-sm
                 transition-colors"
          @click="auth.logout()"
        >
          登出
        </button>
      </div>
    </aside>

    <main class="min-w-0 flex-1 px-5 py-8 sm:px-8 sm:py-10">
      <div class="mx-auto max-w-5xl">
        <slot />
      </div>
    </main>
  </div>
</template>

<style scoped>
/*
 * 後台自己的一組顏色，不套商店的 token。
 *
 * 理由不是美觀而是「走錯地方」這件事的成本：後台改的是正式資料，
 * 而它與商店在同一個網域、同一個應用裡。讓兩者長得像只會讓人
 * 在按下「釋放庫存」之後才發現自己以為還在逛商店。
 *
 * 側欄固定用深色——不跟著主題切換。這是刻意的不一致：
 * 它是一個「你在後台」的恆定訊號，而恆定的訊號不該在淺色模式下消失。
 */
.admin-side {
  background: #10181c;
  color: #c9d6da;
  border-color: #1e2b31;
}

.admin-link {
  color: #93a5ac;
}

.admin-link:hover {
  background: #17232830;
  color: #e7eef0;
}

.admin-side .eyebrow {
  color: #5d7077;
}

.admin-link.is-active {
  background: #17323a;
  color: #6ccfda;
  font-weight: 500;
}
</style>
