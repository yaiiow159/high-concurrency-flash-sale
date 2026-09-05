<script setup lang="ts">
import { errorMessage } from '~/composables/useApi'
import { useAdmin, type SearchReconciliationView } from '~/composables/useAdmin'

/**
 * 維運工具。
 *
 * 這一頁放的是**成本高或不可逆**的操作，因此它與其他後台頁的排版不同：
 * 每一個動作各佔一張卡、先寫清楚它會做什麼、按鈕在最後。
 * 工作佇列式的密集列表在這裡是錯的——那種版面鼓勵連續按下去。
 *
 * 對帳與修復刻意分成兩個按鈕。「先看再修」是唯一安全的順序，
 * 而把它們合成一個「檢查並修復」會讓人在還不知道差異是什麼之前就動手。
 */
definePageMeta({ layout: 'admin', middleware: 'admin', ssr: false })

const { reindex, searchReconciliation } = useAdmin()

const report = ref<SearchReconciliationView | null>(null)
const checking = ref(false)
const repairing = ref(false)
const rebuilding = ref(false)
const message = ref<string | null>(null)
const error = ref<string | null>(null)

async function check(repair: boolean) {
  const flag = repair ? repairing : checking
  if (repair && !confirm('修復索引？\n\n會把缺少的商品補進索引、把不該在的移除。')) {
    return
  }
  flag.value = true
  error.value = null
  message.value = null
  try {
    report.value = await searchReconciliation(repair)
    if (repair) {
      message.value = `已修復 ${report.value.repaired} 筆`
    }
  } catch (cause) {
    error.value = errorMessage(cause, '對帳失敗')
  } finally {
    flag.value = false
  }
}

/**
 * 重建索引。
 *
 * **二次確認，而且訊息要說出成本**：它會把整個商品表重新寫進 Elasticsearch。
 * 商品少的時候幾秒就好，多的時候是一段可觀的時間與寫入量——
 * 而使用者無法從按鈕上看出這件事。
 */
async function rebuild() {
  if (!confirm('重建整份搜尋索引？\n\n會把所有上架商品重新寫進 Elasticsearch，'
    + '商品越多耗時越久。\n重建期間搜尋仍然可用（採用 alias 切換）。')) {
    return
  }
  rebuilding.value = true
  error.value = null
  message.value = null
  try {
    const result = await reindex()
    message.value = `索引重建完成，共 ${result.indexed.toLocaleString()} 筆`
    report.value = null
  } catch (cause) {
    error.value = errorMessage(cause, '重建索引失敗')
  } finally {
    rebuilding.value = false
  }
}

useHead({ title: '維運工具' })
</script>

<template>
  <div>
    <AdminPageHeader title="維運工具" description="成本高或不可逆的操作，都會先問一次" />

    <p
      v-if="error"
      class="mb-4 rounded-sm border border-danger/40 bg-danger-soft p-3 text-sm text-danger"
      role="alert"
    >
      {{ error }}
    </p>
    <p
      v-if="message"
      class="mb-4 rounded-sm border border-ok/40 bg-ok-soft p-3 text-sm"
      :style="{ color: 'var(--ok)' }"
      role="status"
    >
      {{ message }}
    </p>

    <div class="flex flex-col gap-4">
      <AppCard class="p-5">
        <h2 class="text-sm font-semibold">搜尋索引對帳</h2>
        <p class="mt-1.5 max-w-prose text-sm leading-relaxed text-ink-muted">
          比對 Elasticsearch 與資料庫。
          <b>缺少</b>代表商品上架了卻搜不到；
          <b>多餘</b>代表搜得到但其實已經下架——後者更糟，
          使用者會點進一個買不到的商品。
        </p>

        <div class="mt-4 flex flex-wrap gap-2">
          <AppButton size="sm" :disabled="checking || repairing" @click="check(false)">
            {{ checking ? '對帳中⋯' : '執行對帳' }}
          </AppButton>
          <AppButton
            variant="secondary" size="sm"
            :disabled="checking || repairing || report?.balanced !== false"
            @click="check(true)"
          >
            {{ repairing ? '修復中⋯' : '對帳並修復' }}
          </AppButton>
        </div>

        <!-- 沒對帳過就不顯示結果區塊，而不是顯示一堆 0：那會讓人以為已經對過帳了 -->
        <div v-if="report" class="mt-5 border-t border-line pt-4">
          <div class="flex flex-wrap items-center gap-x-6 gap-y-3">
            <div>
              <p class="eyebrow">索引</p>
              <p class="figure text-xl font-semibold">{{ report.indexedCount.toLocaleString() }}</p>
            </div>
            <div>
              <p class="eyebrow">資料庫（上架）</p>
              <p class="figure text-xl font-semibold">{{ report.onShelfCount.toLocaleString() }}</p>
            </div>
            <div>
              <p class="eyebrow">缺少</p>
              <p
                class="figure text-xl font-semibold"
                :class="report.missing.length > 0 ? 'text-danger' : ''"
              >
                {{ report.missing.length }}
              </p>
            </div>
            <div>
              <p class="eyebrow">多餘</p>
              <p
                class="figure text-xl font-semibold"
                :class="report.orphaned.length > 0 ? 'text-danger' : ''"
              >
                {{ report.orphaned.length }}
              </p>
            </div>
            <span
              class="ml-auto rounded-sm px-2 py-1 text-xs"
              :class="report.balanced
                ? 'bg-ok-soft'
                : 'bg-danger-soft text-danger'"
              :style="report.balanced ? { color: 'var(--ok)' } : undefined"
            >
              {{ report.balanced ? '一致' : '不一致' }}
            </span>
          </div>
        </div>
      </AppCard>

      <AppCard class="p-5">
        <h2 class="text-sm font-semibold">重建搜尋索引</h2>
        <p class="mt-1.5 max-w-prose text-sm leading-relaxed text-ink-muted">
          把所有上架商品重新寫進 Elasticsearch。
          採用 alias 切換，<b>重建期間搜尋仍然可用</b>，切換完成才指向新索引。
          平常不需要用它——索引由 Outbox 事件即時維護，
          這是索引結構改變或災難復原時的手段。
        </p>

        <div class="mt-4">
          <AppButton variant="secondary" size="sm" :disabled="rebuilding" @click="rebuild">
            {{ rebuilding ? '重建中⋯' : '重建索引' }}
          </AppButton>
        </div>
      </AppCard>

      <AppCard class="p-5">
        <h2 class="text-sm font-semibold">庫存對帳</h2>
        <p class="mt-1.5 max-w-prose text-sm leading-relaxed text-ink-muted">
          庫存對帳<b>沒有做成按鈕</b>，這是刻意的。它的自動修復預設關閉，
          而唯一會自動處理的情況（孤兒扣減）由排程負責。
          需要人工介入時請走
          <code class="figure text-xs">/api/v1/admin/inventory/reconciliation/*</code>——
          那道摩擦力本身就是保護措施。
        </p>
      </AppCard>
    </div>
  </div>
</template>
