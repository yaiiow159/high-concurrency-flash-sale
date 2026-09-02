<script setup lang="ts">
/**
 * 手機底部固定操作列。
 *
 * <p>電商手機版幾乎必備：主要動作永遠在拇指構得到的地方，
 * 而不是跟著內容捲走。先前「立即購買」在長商品頁上要捲到底才看得到，
 * 那是手機轉換率最常見的漏水點。
 *
 * <p><b>桌機隱藏</b>——那裡有側欄可以固定，不需要再蓋一條。
 *
 * <p>使用這個元件的頁面必須加上 `.pb-action-bar`，
 * 否則最後一段內容會被永久蓋住。
 */
</script>

<template>
  <div
    class="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-surface/95 backdrop-blur
           lg:hidden"
    style="padding-bottom: env(safe-area-inset-bottom)"
  >
    <div class="mx-auto flex max-w-content items-center gap-4 px-4 py-3">
      <div class="min-w-0 flex-1">
        <slot name="info" />
      </div>
      <div class="shrink-0">
        <slot name="action" />
      </div>
    </div>
  </div>
</template>
