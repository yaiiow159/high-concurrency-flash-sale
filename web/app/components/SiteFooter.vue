<script setup lang="ts">
/** 全站頁尾。連結只放真的存在的頁面。 */
const columns = [
  {
    title: '購物',
    links: [
      { to: '/', label: '限時搶購' },
      { to: '/products', label: '全部商品' },
      { to: '/search', label: '搜尋商品' },
      { to: '/coupons', label: '領取優惠券' },
    ],
  },
  {
    title: '我的帳戶',
    links: [
      { to: '/member', label: '會員中心' },
      { to: '/orders', label: '訂單查詢' },
      { to: '/wishlist', label: '收藏清單' },
      { to: '/notifications', label: '通知' },
    ],
  },
  {
    title: '售後服務',
    links: [
      { to: '/returns', label: '退貨與退款' },
      { to: '/reviews', label: '我的評價' },
      { to: '/addresses', label: '收貨地址' },
    ],
  },
] as const
</script>

<template>
  <footer class="mt-14 border-t border-line bg-surface">
    <div class="mx-auto grid max-w-content gap-8 px-5 py-10 sm:grid-cols-2 lg:grid-cols-4">
      <div>
        <p class="flex items-center gap-2 text-lg font-extrabold tracking-tight">
          <span class="grid h-7 w-7 place-items-center rounded-sm bg-accent text-sm text-white">
            閃
          </span>
          閃購
        </p>
        <p class="mt-3 max-w-xs text-sm leading-relaxed text-ink-muted">
          用極少的庫存承接極大的流量。高併發秒殺系統展示站，
          所有交易皆為模擬，不會產生真實付款。
        </p>
        <a
          href="https://github.com/yaiiow159/high-concurrency-flash-sale"
          target="_blank" rel="noopener"
          class="mt-4 inline-flex items-center gap-1.5 text-sm font-medium text-ink-muted
                 transition-colors hover:text-ink"
        >
          <svg viewBox="0 0 24 24" class="h-4 w-4" fill="currentColor" aria-hidden="true">
            <path d="M12 2a10 10 0 0 0-3.2 19.5c.5.1.7-.2.7-.5v-1.8c-2.8.6-3.4-1.2-3.4-1.2-.5-1.2-1.1-1.5-1.1-1.5-.9-.6.1-.6.1-.6 1 .1 1.5 1 1.5 1 .9 1.6 2.4 1.1 3 .9.1-.7.4-1.1.6-1.4-2.2-.3-4.6-1.1-4.6-5 0-1.1.4-2 1-2.7-.1-.3-.4-1.3.1-2.7 0 0 .8-.3 2.8 1a9.5 9.5 0 0 1 5 0c1.9-1.3 2.7-1 2.7-1 .6 1.4.2 2.4.1 2.7.7.7 1 1.6 1 2.7 0 3.9-2.4 4.7-4.6 5 .4.3.7.9.7 1.9v2.8c0 .3.2.6.7.5A10 10 0 0 0 12 2Z" />
          </svg>
          原始碼
        </a>
      </div>

      <nav v-for="column in columns" :key="column.title" :aria-label="column.title">
        <p class="eyebrow mb-3">{{ column.title }}</p>
        <ul class="flex flex-col gap-2">
          <li v-for="link in column.links" :key="link.to">
            <NuxtLink
              :to="link.to"
              class="text-sm text-ink-muted transition-colors hover:text-ink"
            >
              {{ link.label }}
            </NuxtLink>
          </li>
        </ul>
      </nav>
    </div>
    <div class="border-t border-line">
      <p class="mx-auto max-w-content px-5 py-4 text-xs text-ink-faint">
        © 2026 閃購 FLASH SALE · 展示用途，所有交易皆為模擬
      </p>
    </div>
  </footer>
</template>
