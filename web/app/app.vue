<template>
  <!-- 慢網路下點了連結沒有任何反應，使用者會再點一次 -->
  <NuxtLoadingIndicator color="var(--accent)" :height="2" :throttle="150" />
  <NuxtLayout>
    <NuxtPage />
  </NuxtLayout>
  <AppToaster />
</template>
