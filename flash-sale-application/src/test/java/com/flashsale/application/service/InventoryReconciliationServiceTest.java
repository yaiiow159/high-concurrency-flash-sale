package com.flashsale.application.service;

import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.port.in.dto.SkuReconciliation;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.InventoryRepository.LedgerTotals;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.stock.ReconciliationVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一般庫存對帳。
 *
 * <p>核對的是「數字」與「流水」：庫存欄位上的每個數字，
 * 都必須有一連串異動紀錄能解釋它是怎麼來的。
 *
 * <p><b>這裡的測試同時鎖住「絕不自動修」這條規則。</b>
 * 一旦有人為了「讓告警安靜」而加上自動修復，這幾條測試就會失敗——
 * 那正是它們存在的理由。這個方向的偏差代表有東西繞過了正規路徑，
 * 既然連正規路徑都沒被遵守，對帳也就無從判斷哪一邊才是對的。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("一般庫存對帳")
class InventoryReconciliationServiceTest {

    private static final long SKU = 2001L;

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryMetrics metrics;

    @Nested
    @DisplayName("恆等式")
    class Identity {

        @Test
        @DisplayName("數字與流水相符時帳平，且不佔用回傳值")
        void balancedIsNotReported() {
            givenSku(SKU, 4000, 1000, new LedgerTotals(4000, 1000));

            List<SkuReconciliation> unbalanced = service().reconcileAll();

            assertThat(unbalanced).isEmpty();
        }

        @Test
        @DisplayName("可售量比流水多：有人繞過正規路徑加了庫存")
        void detectsAvailableSurplus() {
            givenSku(SKU, 4050, 1000, new LedgerTotals(4000, 1000));

            SkuReconciliation result = service().reconcile(SKU);

            assertThat(result.availableDrift()).isEqualTo(50);
            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.OVERSELL_RISK);
            assertThat(result.isBalanced()).isFalse();
        }

        @Test
        @DisplayName("可售量比流水少：有庫存被扣掉卻沒留下紀錄")
        void detectsAvailableShortfall() {
            givenSku(SKU, 3950, 1000, new LedgerTotals(4000, 1000));

            SkuReconciliation result = service().reconcile(SKU);

            assertThat(result.availableDrift()).isEqualTo(-50);
            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.STOCK_LEAKED);
        }

        @Test
        @DisplayName("劃撥量對不上也算不平——只看可售量會漏掉整條劃撥路徑的錯誤")
        void detectsAllocatedDrift() {
            givenSku(SKU, 4000, 1000, new LedgerTotals(4000, 900));

            SkuReconciliation result = service().reconcile(SKU);

            assertThat(result.availableDrift()).isZero();
            assertThat(result.allocatedDrift()).isEqualTo(100);
            assertThat(result.isBalanced()).isFalse();
        }

        @Test
        @DisplayName("有庫存數字卻完全沒有流水：那批貨是憑空出現的，必須報出來")
        void treatsMissingLedgerAsZeroNotAsSkip() {
            when(inventoryRepository.findSkuIdsForReconciliation(anyInt(), anyInt()))
                    .thenReturn(List.of(SKU), List.of());
            when(inventoryRepository.findBySkuIds(List.of(SKU)))
                    .thenReturn(List.of(Inventory.restore(SKU, 500, 0, 0L)));
            // 這個 SKU 在流水彙總的結果裡完全不存在
            when(inventoryRepository.sumLedgerBySkuIds(any())).thenReturn(Map.of());

            List<SkuReconciliation> unbalanced = service().reconcileAll();

            assertThat(unbalanced).hasSize(1);
            assertThat(unbalanced.get(0).availableDrift()).isEqualTo(500);
        }
    }

    @Nested
    @DisplayName("什麼情況絕不自動修")
    class NeverAutoRepairs {

        @Test
        @DisplayName("偏差再大也只讀不寫——對帳不得改動任何庫存")
        void reconciliationNeverWrites() {
            givenSku(SKU, 100, 0, new LedgerTotals(9999, 0));

            service().reconcileAll();

            verify(inventoryRepository, never()).save(any());
            verify(inventoryRepository, never()).recordMovement(any());
            verify(inventoryRepository, never()).createIfAbsent(any(), anyInt());
        }
    }

    @Nested
    @DisplayName("掃描")
    class Scanning {

        @Test
        @DisplayName("分批掃到底：最後一批不足一頁才停")
        void scansUntilShortPage() {
            ReconciliationPolicy policy = new ReconciliationPolicy(java.time.Duration.ofMinutes(30), 2, false);
            when(inventoryRepository.findSkuIdsForReconciliation(2, 0)).thenReturn(List.of(1L, 2L));
            when(inventoryRepository.findSkuIdsForReconciliation(2, 2)).thenReturn(List.of(3L));
            when(inventoryRepository.findBySkuIds(any())).thenReturn(List.of());
            when(inventoryRepository.sumLedgerBySkuIds(any())).thenReturn(Map.of());

            new InventoryReconciliationService(inventoryRepository, metrics, policy).reconcileAll();

            verify(inventoryRepository).findSkuIdsForReconciliation(2, 0);
            verify(inventoryRepository).findSkuIdsForReconciliation(2, 2);
            // 第二批不滿一頁就該停，不能再多問一次
            verify(inventoryRepository, never()).findSkuIdsForReconciliation(2, 4);
        }

        @Test
        @DisplayName("單筆查詢帳平的 SKU 要回真實數字，不能回全零")
        void singleLookupReturnsActualNumbersWhenBalanced() {
            // 全量掃描只回不平的；若單筆查詢共用同一份過濾結果，
            // 帳平的 SKU 會查不到而退回預設值，維運看到的就是「庫存消失了」
            givenSku(SKU, 4000, 1000, new LedgerTotals(4000, 1000));

            SkuReconciliation result = service().reconcile(SKU);

            assertThat(result.isBalanced()).isTrue();
            assertThat(result.available()).isEqualTo(4000);
            assertThat(result.allocated()).isEqualTo(1000);
        }

        @Test
        @DisplayName("尚未建帳的 SKU 回全零而非拋例外——查一個還沒進貨的商品是合理操作")
        void unknownSkuReturnsZeroes() {
            when(inventoryRepository.findBySkuIds(List.of(9999L))).thenReturn(List.of());
            when(inventoryRepository.sumLedgerBySkuIds(any())).thenReturn(Map.of());

            SkuReconciliation result = service().reconcile(9999L);

            assertThat(result.isBalanced()).isTrue();
            assertThat(result.available()).isZero();
        }
    }

    // ---- fixtures ----

    private InventoryReconciliationService service() {
        return new InventoryReconciliationService(inventoryRepository, metrics, policy());
    }

    private void givenSku(Long skuId, int available, int allocated, LedgerTotals totals) {
        when(inventoryRepository.findSkuIdsForReconciliation(anyInt(), anyInt()))
                .thenReturn(List.of(skuId), List.of());
        when(inventoryRepository.findBySkuIds(List.of(skuId)))
                .thenReturn(List.of(Inventory.restore(skuId, available, allocated, 0L)));
        when(inventoryRepository.sumLedgerBySkuIds(List.of(skuId)))
                .thenReturn(Map.of(skuId, totals));
    }

    private ReconciliationPolicy policy() {
        return new ReconciliationPolicy(java.time.Duration.ofMinutes(30), 500, false);
    }
}
