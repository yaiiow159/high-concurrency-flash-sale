<script setup lang="ts">
import type { CarouselSlideView } from '~/types/api'

const props = defineProps<{ slides: CarouselSlideView[] }>()

const active = ref(0)
const paused = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const INTERVAL_MS = 5000

function go(index: number) {
  active.value = (index + props.slides.length) % props.slides.length
}

/**
 * 自動輪播。只在多於一張、使用者沒要求減少動態、且分頁在前景時才跑。
 * 背景分頁繼續換頁只會白耗電，而使用者回來看到的還是同一格。
 */
function start() {
  stop()
  if (props.slides.length <= 1 || paused.value) {
    return
  }
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return
  }
  timer = setInterval(() => go(active.value + 1), INTERVAL_MS)
}

function stop() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function setPaused(value: boolean) {
  paused.value = value
  start()
}

function onVisibilityChange() {
  setPaused(document.hidden)
}

onMounted(() => {
  start()
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onBeforeUnmount(() => {
  stop()
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

watch(() => props.slides.length, start)
</script>

<template>
  <section
    v-if="slides.length > 0"
    class="relative overflow-hidden rounded-lg border border-line bg-surface shadow-rest"
    aria-roledescription="carousel"
    aria-label="主視覺輪播"
    @mouseenter="setPaused(true)"
    @mouseleave="setPaused(false)"
    @focusin="setPaused(true)"
    @focusout="setPaused(false)"
  >
    <div class="relative aspect-[21/9] w-full sm:aspect-[3/1]">
      <component
        :is="slide.linkUrl ? 'NuxtLink' : 'div'"
        v-for="(slide, index) in slides"
        :key="slide.slideId"
        :to="slide.linkUrl || undefined"
        class="absolute inset-0 transition-opacity duration-500"
        :class="index === active ? 'opacity-100' : 'pointer-events-none opacity-0'"
        :aria-hidden="index === active ? undefined : 'true'"
        :tabindex="index === active ? undefined : -1"
      >
        <img
          :src="slide.imageUrl"
          :alt="slide.title ?? ''"
          class="h-full w-full object-cover"
          :loading="index === 0 ? 'eager' : 'lazy'"
        >
        <div
          v-if="slide.title"
          class="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/65 to-transparent
                 px-4 py-4 sm:px-6 sm:py-6"
        >
          <p class="text-base font-semibold text-white drop-shadow sm:text-xl">
            {{ slide.title }}
          </p>
        </div>
      </component>
    </div>

    <!-- 只有一張時不顯示控制項：一格的輪播看起來像壞掉 -->
    <template v-if="slides.length > 1">
      <button
        type="button"
        class="absolute left-2 top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center
               rounded-full bg-black/35 text-white transition-colors hover:bg-black/55"
        aria-label="上一張"
        @click="go(active - 1)"
      >
        ‹
      </button>
      <button
        type="button"
        class="absolute right-2 top-1/2 grid h-9 w-9 -translate-y-1/2 place-items-center
               rounded-full bg-black/35 text-white transition-colors hover:bg-black/55"
        aria-label="下一張"
        @click="go(active + 1)"
      >
        ›
      </button>

      <ul class="absolute inset-x-0 bottom-2 flex justify-center gap-1.5">
        <li v-for="(slide, index) in slides" :key="slide.slideId">
          <button
            type="button"
            class="h-1.5 rounded-full transition-all"
            :class="index === active ? 'w-5 bg-white' : 'w-1.5 bg-white/55 hover:bg-white/80'"
            :aria-label="`第 ${index + 1} 張`"
            :aria-current="index === active"
            @click="go(index)"
          />
        </li>
      </ul>
    </template>
  </section>
</template>
