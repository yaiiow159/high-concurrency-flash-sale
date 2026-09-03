<script setup lang="ts">
import type { ReturnRequestView } from '~/types/api'

/**
 * 退貨進度。
 *
 * <b>免寄回的申請不顯示「寄回商品」那一步。</b>
 * 畫一個永遠不會亮的步驟，使用者會一直在等它——
 * 而它不會來，因為那張單根本不需要寄回任何東西。
 *
 * 被駁回與撤回不畫進度條：它們不是「停在某一步」，
 * 是整條路走完了但結局不同。把它們塞進同一條線上，
 * 會讓人以為還有後續。
 */
const props = defineProps<{ request: ReturnRequestView }>()

interface Step {
  label: string
  hint: string
  at?: string | null
  done: boolean
}

const CLOSED = ['REJECTED', 'CANCELLED']

const closed = computed(() => CLOSED.includes(props.request.status))

const steps = computed<Step[]>(() => {
  const r = props.request
  const list: Step[] = [
    {
      label: '已提出申請',
      hint: '等待客服審核',
      at: r.createdAt,
      done: true,
    },
    {
      label: '客服已核准',
      hint: r.requiresGoodsReturn ? '請將商品寄回' : '免寄回，將直接退款',
      at: r.reviewedAt,
      // 用 != null 而不是 !== null：後端省略 null 欄位，收到的是 undefined，
      // 而 undefined !== null 為 true——那會讓還沒發生的步驟全部亮起來
      done: r.reviewedAt != null,
    },
  ]

  // 免寄回時整個「驗收」概念不存在，不是「還沒發生」
  if (r.requiresGoodsReturn) {
    list.push({
      label: '已收到退回商品',
      hint: '完成驗收後即可退款',
      at: r.receivedAt,
      done: r.receivedAt != null,
    })
  }

  list.push({
    label: '退款已送出',
    hint: '款項將依原付款方式退回',
    at: r.refundedAt,
    done: r.refundedAt != null,
  })
  return list
})

function formatDate(value?: string | null): string {
  if (!value) {
    return ''
  }
  return new Date(value).toLocaleString('zh-TW', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div
    v-if="closed"
    class="rounded-sm border border-line bg-sunken p-4 text-sm text-ink-muted"
  >
    <p>
      這張退貨單已{{ request.status === 'REJECTED' ? '被駁回' : '撤回' }}，流程結束。
    </p>
    <p v-if="request.reviewNote" class="mt-2 text-ink">{{ request.reviewNote }}</p>
  </div>

  <ol v-else class="flex flex-col">
    <li
      v-for="(step, index) in steps"
      :key="step.label"
      class="relative flex gap-4 pb-6 last:pb-0"
    >
      <!-- 連接線畫在項目上而不是獨立元素：最後一項不畫，
           否則線會從最後一個圓點往下垂出一截 -->
      <span
        v-if="index < steps.length - 1"
        class="absolute left-[7px] top-4 h-full w-px"
        :class="step.done ? 'bg-cta' : 'bg-line'"
        aria-hidden="true"
      />
      <span
        class="relative mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2"
        :class="step.done ? 'border-cta bg-cta' : 'border-line bg-surface'"
        aria-hidden="true"
      />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-baseline justify-between gap-x-3">
          <p class="font-medium" :class="step.done ? 'text-ink' : 'text-ink-faint'">
            {{ step.label }}
          </p>
          <span v-if="step.at" class="figure text-xs text-ink-faint">
            {{ formatDate(step.at) }}
          </span>
        </div>
        <p class="mt-0.5 text-sm" :class="step.done ? 'text-ink-muted' : 'text-ink-faint'">
          {{ step.hint }}
        </p>
      </div>
    </li>
  </ol>
</template>
