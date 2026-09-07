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

/** 只在多於一張、使用者沒要求減少動態、且分頁在前景時才自動輪播。 */
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

/** 圖片載不出來的格子退回品牌漸層。破圖圖示在首頁最顯眼的位置是不能接受的。 */
const failed = ref<Record<number, boolean>>({})
</script>

<template>
  <section
    v-if="slides.length > 0"
    class="group relative h-full overflow-hidden rounded bg-ink-inverse shadow-rest"
    aria-roledescription="carousel"
    aria-label="主視覺輪播"
    @mouseenter="setPaused(true)"
    @mouseleave="setPaused(false)"
    @focusin="setPaused(true)"
    @focusout="setPaused(false)"
  >
    <div class="relative aspect-[16/7] w-full sm:aspect-[21/8] lg:aspect-[3/1]">
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
          v-if="!failed[slide.slideId]"
          :src="slide.imageUrl"
          :alt="slide.title ?? ''"
          class="h-full w-full object-cover"
          :loading="index === 0 ? 'eager' : 'lazy'"
          @error="failed[slide.slideId] = true"
        >
        <div v-else class="bg-promo h-full w-full" />
        <div
          v-if="slide.title"
          class="absolute inset-x-0 bottom-0 bg-gradient-to-t from-black/70 via-black/25 to-transparent
                 px-5 pb-5 pt-16 sm:px-8 sm:pb-7"
        >
          <p class="text-xl font-extrabold text-white drop-shadow sm:text-3xl">
            {{ slide.title }}
          </p>
          <p v-if="slide.linkUrl" class="mt-1.5 text-sm font-medium text-white/85">
            立即查看 →
          </p>
        </div>
      </component>
    </div>

    <!-- 只有一張時不顯示控制項：一格的輪播看起來像壞掉 -->
    <template v-if="slides.length > 1">
      <button
        type="button"
        class="absolute left-3 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center
               rounded-full bg-white/90 text-ink opacity-0 shadow-rest transition
               hover:bg-white group-hover:opacity-100 focus-visible:opacity-100"
        aria-label="上一張"
        @click="go(active - 1)"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2.2">
          <path d="m15 5-7 7 7 7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
      <button
        type="button"
        class="absolute right-3 top-1/2 grid h-10 w-10 -translate-y-1/2 place-items-center
               rounded-full bg-white/90 text-ink opacity-0 shadow-rest transition
               hover:bg-white group-hover:opacity-100 focus-visible:opacity-100"
        aria-label="下一張"
        @click="go(active + 1)"
      >
        <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2.2">
          <path d="m9 5 7 7-7 7" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>

      <ul class="absolute inset-x-0 bottom-3 flex justify-center gap-1.5">
        <li v-for="(slide, index) in slides" :key="slide.slideId">
          <button
            type="button"
            class="h-1.5 rounded-full transition-all"
            :class="index === active ? 'w-6 bg-white' : 'w-1.5 bg-white/55 hover:bg-white/80'"
            :aria-label="`第 ${index + 1} 張`"
            :aria-current="index === active"
            @click="go(index)"
          />
        </li>
      </ul>
    </template>
  </section>
</template>
