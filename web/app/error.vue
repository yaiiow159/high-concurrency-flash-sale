<script setup lang="ts">
import type { NuxtError } from '#app'

/**
 * 錯誤頁。刻意不套用全站版型：頁首會去抓分類，
 * 而這一頁最常出現的時機正是後端掛掉的時候——錯誤頁自己不能再出錯。
 */
const props = defineProps<{ error: NuxtError }>()

const notFound = computed(() => props.error.statusCode === 404)

const copy = computed(() => notFound.value
  ? {
      title: '找不到這一頁',
      hint: '連結可能已經失效，或商品已經下架。試試搜尋，或回首頁看看今天的限時搶購。',
    }
  : {
      title: '系統暫時忙不過來',
      hint: '不是你的問題。稍等一下再重新整理；已經送出的訂單不會受影響，可以到「我的訂單」確認。',
    })

const keyword = ref('')

function search() {
  const q = keyword.value.trim()
  void clearError({ redirect: q ? `/search?q=${encodeURIComponent(q)}` : '/search' })
}

function goHome() {
  void clearError({ redirect: '/' })
}

function reload() {
  window.location.reload()
}

useHead({ title: () => (notFound.value ? '找不到頁面' : '系統忙碌中') })
</script>

<template>
  <div class="flex min-h-screen flex-col bg-ground">
    <header class="border-b border-line bg-surface">
      <div class="mx-auto flex h-16 max-w-content items-center px-5">
        <a href="/" class="flex items-center gap-2" aria-label="閃購首頁" @click.prevent="goHome">
          <span class="grid h-9 w-9 place-items-center rounded bg-accent text-lg font-extrabold text-white">
            閃
          </span>
          <span class="flex flex-col leading-none">
            <span class="text-lg font-extrabold tracking-tight text-ink">閃購</span>
            <span class="text-[10px] font-bold tracking-[0.18em] text-accent">FLASH SALE</span>
          </span>
        </a>
      </div>
    </header>

    <main class="mx-auto flex w-full max-w-xl flex-1 flex-col items-center justify-center px-5 py-16 text-center">
      <p class="figure select-none text-[7rem] font-extrabold leading-none tracking-tighter text-accent/15 sm:text-[9rem]">
        {{ error.statusCode }}
      </p>
      <h1 class="-mt-6 text-2xl font-extrabold tracking-tight sm:-mt-8 sm:text-3xl">{{ copy.title }}</h1>
      <p class="mt-3 max-w-md text-sm leading-relaxed text-ink-muted">{{ copy.hint }}</p>

      <form
        v-if="notFound"
        class="mt-8 flex w-full max-w-md items-center overflow-hidden rounded-sm border-2 border-accent bg-surface"
        role="search"
        @submit.prevent="search"
      >
        <label for="error-search" class="sr-only">搜尋商品</label>
        <input
          id="error-search" v-model="keyword" type="search" placeholder="搜尋商品、品牌"
          autocomplete="off"
          class="h-11 min-w-0 flex-1 bg-transparent px-3 text-sm placeholder:text-ink-faint focus:outline-none"
        >
        <button
          type="submit"
          class="h-11 shrink-0 bg-accent px-5 text-sm font-semibold text-white transition-colors hover:bg-accent-hover"
        >
          搜尋
        </button>
      </form>

      <div class="mt-8 flex flex-wrap items-center justify-center gap-3">
        <AppButton v-if="!notFound" size="lg" @click="reload">重新整理</AppButton>
        <AppButton :variant="notFound ? 'primary' : 'secondary'" size="lg" @click="goHome">
          回首頁
        </AppButton>
        <AppButton
          variant="secondary" size="lg"
          @click="clearError({ redirect: notFound ? '/products' : '/orders' })"
        >
          {{ notFound ? '逛逛全部商品' : '我的訂單' }}
        </AppButton>
      </div>
    </main>

    <p class="pb-8 text-center text-xs text-ink-faint">展示站 · 所有交易皆為模擬</p>
  </div>
</template>
