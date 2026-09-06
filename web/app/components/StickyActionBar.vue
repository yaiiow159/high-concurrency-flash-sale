<script setup lang="ts">
/** 手機底部固定操作列。 */
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
