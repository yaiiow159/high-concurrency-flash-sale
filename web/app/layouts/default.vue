<script setup lang="ts">
/**
 * 全站版型。
 *
 * 頁首與內容寬度集中在這裡，各頁只寫自己的內容——
 * 先前每頁各自寫 `mx-auto max-w-3xl px-5 py-10`，
 * 於是有的頁面是 max-w-3xl、有的是 max-w-4xl，切換時整個版面會抖一下。
 */
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <AppHeader />
    <main class="mx-auto w-full max-w-content flex-1 px-5 py-8 sm:py-12">
      <slot />
    </main>
    <footer class="border-t border-line px-5 py-6">
      <p class="mx-auto max-w-content text-xs text-ink-faint">
        高併發秒殺系統 · 展示用途，所有交易皆為模擬
      </p>
    </footer>
  </div>
</template>
