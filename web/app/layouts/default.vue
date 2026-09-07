<script setup lang="ts">
/** 全站版型。頁首、內容寬度與頁尾集中在這裡，各頁只寫自己的內容。 */
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <AppHeader />
    <main class="mx-auto w-full max-w-content flex-1 px-4 py-5 sm:px-5 sm:py-7">
      <slot />
    </main>
    <SiteFooter />
  </div>
</template>
