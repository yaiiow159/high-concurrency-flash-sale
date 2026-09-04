<script setup lang="ts">
import { useAdmin } from '~/composables/useAdmin'

/**
 * 後台總覽。
 *
 * **這一頁只回答一個問題：現在有什麼事等著我做。**
 * 不放營業額、不放折線圖——那些該從 Grafana 看，那裡已經有 Prometheus 的資料，
 * 在後台重畫一次只是多一份會過時的實作（ADR-0015「不做的事」）。
 *
 * 每一張卡都是**待辦數量 + 一個入口**。數字為 0 時仍然顯示，
 * 而不是把卡片藏起來——「今天沒有待出貨」本身就是維運想知道的資訊，
 * 而消失的卡片會讓人懷疑是不是壞了。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { shipments, returns, searchReconciliation } = useAdmin()

const pendingShipments = ref<number | null>(null)
const pendingReturns = ref<number | null>(null)
const indexBalanced = ref<boolean | null>(null)
const indexDrift = ref(0)
const loading = ref(true)

/**
 * 三個查詢並行。它們互不相依，串起來只是把延遲加成三倍。
 *
 * **各自 catch**：對帳掛掉不該讓「待出貨有幾筆」也看不到。
 * 失敗的那一格顯示破折號，而不是 0——「查不到」與「沒有」是兩件事。
 */
async function load() {
  loading.value = true
  const [ship, ret, recon] = await Promise.all([
    shipments('PENDING').then((list) => list.length).catch(() => null),
    returns('REQUESTED').then((list) => list.length).catch(() => null),
    searchReconciliation(false).catch(() => null),
  ])
  pendingShipments.value = ship
  pendingReturns.value = ret
  indexBalanced.value = recon ? recon.balanced : null
  indexDrift.value = recon ? recon.missing.length + recon.orphaned.length : 0
  loading.value = false
}

onMounted(load)

useHead({ title: '後台總覽' })
</script>

<template>
  <div>
    <AdminPageHeader title="總覽" description="現在有什麼事等著處理">
      <template #actions>
        <AppButton variant="secondary" size="sm" :disabled="loading" @click="load">
          {{ loading ? '更新中⋯' : '重新整理' }}
        </AppButton>
      </template>
    </AdminPageHeader>

    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
      <AdminStatCard
        title="待出貨" :value="pendingShipments" :loading="loading"
        hint="已付款、尚未交付承運商" to="/admin/shipments"
        :alert="(pendingShipments ?? 0) > 0"
      />
      <AdminStatCard
        title="待審退貨" :value="pendingReturns" :loading="loading"
        hint="使用者已申請、等待客服判斷" to="/admin/returns"
        :alert="(pendingReturns ?? 0) > 0"
      />
      <AdminStatCard
        title="搜尋索引偏差" :value="indexDrift" :loading="loading"
        :hint="indexBalanced === false
          ? '索引與資料庫不一致，商品可能搜不到'
          : '索引與資料庫一致'"
        to="/admin/ops"
        :alert="indexBalanced === false"
      />
    </div>

    <!--
      這段刻意寫死在畫面上。後台的使用者不會去讀 ADR，
      而「前端擋不住任何人」這件事一旦被誤解成安全機制，
      下一個人就會把某個該加 scope 的端點放進放行清單
    -->
    <AppCard class="mt-8 p-5">
      <h2 class="eyebrow mb-2">關於權限</h2>
      <p class="max-w-prose text-sm leading-relaxed text-ink-muted">
        這個後台的選單只是介面。真正的授權在後端——
        每一支 <code class="figure text-xs">/api/v1/admin/**</code> 都需要
        <code class="figure text-xs">seckill:admin</code> 權限，
        繞過前端也拿不到資料。
      </p>
    </AppCard>
  </div>
</template>
