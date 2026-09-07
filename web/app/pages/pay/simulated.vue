<script setup lang="ts">
import { errorMessage, useApi } from '~/composables/useApi'
import { useAuthStore } from '~/stores/auth'
import type { PaymentView } from '~/types/api'

/**
 * 模擬金流頁。真實金流是離站到銀行的頁面再回來，這一頁扮演那個「銀行頁」。
 * 它不碰簽章也不送回調——回調由後端的模擬閘道自己發，這裡只負責把狀態
 * 輪詢出來讓使用者看到結果，而不是把人丟在一頁 JSON 上。
 */
definePageMeta({ layout: false })

const route = useRoute()
const auth = useAuthStore()
const { request } = useApi()

const paymentNo = computed(() => String(route.query.paymentNo ?? ''))
const orderNo = computed(() => String(route.query.orderNo ?? ''))
const amount = computed(() => {
  const raw = Number(route.query.amount)
  return Number.isFinite(raw) ? raw : null
})

type Phase = 'ready' | 'processing' | 'succeeded' | 'failed' | 'timeout'
const phase = ref<Phase>('ready')
const failure = ref<string | null>(null)

/** 輪詢上限。模擬閘道兩秒就回調，十五秒還沒結果代表別的地方出了問題。 */
const POLL_INTERVAL_MS = 1000
const POLL_LIMIT = 15

async function confirm() {
  phase.value = 'processing'
  for (let attempt = 0; attempt < POLL_LIMIT; attempt++) {
    try {
      const payment = await request<PaymentView>(
        `/api/v1/orders/${orderNo.value}/payments`, { authenticated: true })
      if (payment.status === 'SUCCEEDED') {
        phase.value = 'succeeded'
        return
      }
      if (payment.status === 'FAILED') {
        phase.value = 'failed'
        failure.value = payment.failureReason
        return
      }
    } catch (cause) {
      phase.value = 'failed'
      failure.value = errorMessage(cause, '查詢付款狀態失敗')
      return
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS))
  }
  phase.value = 'timeout'
}

const invalid = computed(() => !paymentNo.value || !orderNo.value)

useHead({ title: '模擬付款' })
</script>

<template>
  <div class="flex min-h-screen flex-col bg-[#eef0f4]">
    <!-- 刻意不用商店的頁首：離站到金流商的頁面本來就不長得像商店 -->
    <header class="border-b border-line bg-surface">
      <div class="mx-auto flex h-14 max-w-2xl items-center justify-between px-5">
        <p class="flex items-center gap-2 text-sm font-bold">
          <span class="grid h-7 w-7 place-items-center rounded-sm bg-ink-inverse text-xs text-white">
            SIM
          </span>
          Simulated Payment Gateway
        </p>
        <span class="rounded-full bg-ok-soft px-2.5 py-1 text-[11px] font-semibold text-ok">
          測試環境 · 不會扣款
        </span>
      </div>
    </header>

    <main class="mx-auto w-full max-w-2xl flex-1 px-5 py-10">
      <AppCard v-if="invalid" class="p-6">
        <p class="font-semibold">付款連結不完整。</p>
        <p class="mt-1 text-sm text-ink-muted">請回到訂單頁重新發起付款。</p>
        <AppButton class="mt-5" variant="secondary" size="sm" @click="navigateTo('/orders')">
          我的訂單
        </AppButton>
      </AppCard>

      <AppCard v-else-if="!auth.isAuthenticated" class="p-6">
        <p class="font-semibold">請先登入</p>
        <p class="mt-1 text-sm text-ink-muted">付款狀態屬於你的訂單，要登入後才查得到。</p>
        <AuthPanel class="mt-5" />
      </AppCard>

      <AppCard v-else class="overflow-hidden">
        <div class="border-b border-line bg-sunken px-6 py-5">
          <p class="eyebrow">付款金額</p>
          <MoneyText :amount="amount" size="xl" class="mt-1" />
          <dl class="mt-4 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-xs text-ink-muted">
            <dt>訂單編號</dt>
            <dd class="figure text-ink">{{ orderNo }}</dd>
            <dt>付款單號</dt>
            <dd class="figure text-ink">{{ paymentNo }}</dd>
          </dl>
        </div>

        <div class="p-6">
          <template v-if="phase === 'ready'">
            <p class="text-sm leading-relaxed text-ink-muted">
              這是模擬的金流頁，<b class="text-ink">不需要輸入任何卡號</b>。
              按下確認後會等待閘道回調，完成即導回訂單。
            </p>
            <div class="mt-6 flex flex-col gap-2 sm:flex-row">
              <AppButton size="lg" class="flex-1" @click="confirm">確認付款</AppButton>
              <AppButton
                variant="secondary" size="lg" @click="navigateTo(`/orders/${orderNo}`)"
              >
                取消，回訂單
              </AppButton>
            </div>
          </template>

          <div v-else-if="phase === 'processing'" class="flex items-center gap-3 py-2" role="status">
            <span
              class="h-5 w-5 animate-spin rounded-full border-2 border-line-strong border-t-accent"
              aria-hidden="true"
            />
            <p class="text-sm text-ink-muted">等待金流閘道確認中⋯</p>
          </div>

          <div v-else-if="phase === 'succeeded'" role="status">
            <div class="flex items-center gap-3">
              <span class="grid h-10 w-10 place-items-center rounded-full bg-ok-soft text-ok">
                <svg viewBox="0 0 24 24" class="h-5 w-5" fill="none" stroke="currentColor" stroke-width="2.4">
                  <path d="m5 12 5 5 9-10" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <div>
                <p class="font-bold text-ok">付款成功</p>
                <p class="text-sm text-ink-muted">訂單已進入出貨流程。</p>
              </div>
            </div>
            <AppButton class="mt-6" size="lg" block @click="navigateTo(`/orders/${orderNo}`)">
              查看訂單
            </AppButton>
          </div>

          <div v-else-if="phase === 'failed'" role="alert">
            <p class="font-bold text-danger">付款失敗</p>
            <p class="mt-1 text-sm text-ink-muted">{{ failure ?? '閘道回報付款未完成。' }}</p>
            <div class="mt-6 flex gap-2">
              <AppButton variant="secondary" @click="navigateTo(`/orders/${orderNo}`)">回訂單</AppButton>
            </div>
          </div>

          <!-- 逾時不等於失敗：回調可能只是慢，讓使用者去訂單頁確認，不要在這裡無限等 -->
          <div v-else role="status">
            <p class="font-bold">還在處理中</p>
            <p class="mt-1 text-sm text-ink-muted">
              閘道尚未回覆。付款結果稍後會反映在訂單上，請到訂單頁確認。
            </p>
            <AppButton class="mt-6" variant="secondary" @click="navigateTo(`/orders/${orderNo}`)">
              回訂單
            </AppButton>
          </div>
        </div>
      </AppCard>

      <p class="mt-6 text-center text-xs text-ink-faint">
        展示用途 · 所有交易皆為模擬，不會產生真實付款
      </p>
    </main>
  </div>
</template>
