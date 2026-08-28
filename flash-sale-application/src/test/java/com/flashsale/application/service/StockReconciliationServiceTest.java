package com.flashsale.application.service;

import com.flashsale.application.config.ReconciliationPolicy;
import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.dto.ActivityReconciliation;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.stock.ReconciliationVerdict;
import com.flashsale.domain.stock.StockBinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 庫存對帳的單元測試。
 *
 * <p>這裡的測試比一般功能測試更關鍵：對帳一旦算錯又搭配自動修復，
 * 就會拿著錯誤的結論去改動正確的資料——<b>破壞力大於它本來要修的問題</b>。
 * 因此偏差方向、寬限期、以及「什麼情況下絕不自動修」都必須逐一釘死。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("庫存對帳")
class StockReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Long ACTIVITY_ID = 1001L;
    private static final int TOTAL_STOCK = 1000;

    @Mock private ActivityRepository activityRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private StockRepository stockRepository;
    @Mock private OrderNoGenerator orderNoGenerator;
    @Mock private SoldOutMarker soldOutMarker;
    @Mock private SeckillMetrics metrics;

    @Nested
    @DisplayName("偏差判定")
    class DriftDetection {

        @Test
        @DisplayName("Redis 餘量 + 有效訂單量 = 總庫存：帳平")
        void reportsBalanced() {
            givenActivity();
            when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(700L);
            when(orderRepository.sumActiveQuantity(ACTIVITY_ID)).thenReturn(300L);
            givenNoBindings();

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.BALANCED);
            assertThat(result.drift()).isZero();
        }

        @Test
        @DisplayName("Redis 餘量少於應有值：判定為庫存洩漏（少賣）")
        void detectsStockLeak() {
            givenActivity();
            // 訂單只佔了 300，Redis 卻只剩 650 —— 有 50 件被扣掉卻沒有訂單
            when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(650L);
            when(orderRepository.sumActiveQuantity(ACTIVITY_ID)).thenReturn(300L);
            givenNoBindings();

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.STOCK_LEAKED);
            assertThat(result.drift()).isEqualTo(-50);
        }

        @Test
        @DisplayName("Redis 餘量多於應有值：判定為超賣風險")
        void detectsOversellRisk() {
            givenActivity();
            // 餘量 750 + 訂單 300 = 1050 > 總庫存 1000，等於憑空多出 50 件可賣
            when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(750L);
            when(orderRepository.sumActiveQuantity(ACTIVITY_ID)).thenReturn(300L);
            givenNoBindings();

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.OVERSELL_RISK);
            assertThat(result.drift()).isEqualTo(50);
        }

        @Test
        @DisplayName("未預熱不算異常：活動可能剛建立或早已結束")
        void treatsUninitializedAsNonIssue() {
            givenActivity();
            when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(-1L);

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.NOT_INITIALIZED);
            assertThat(result.verdict().requiresAttention()).isFalse();
            // 未預熱時不該再去掃描綁定，白費一次 Redis 掃描
            verify(stockRepository, never()).scanBindings(anyLong(), anyInt(), any());
        }
    }

    @Nested
    @DisplayName("孤兒扣減")
    class OrphanBindings {

        @Test
        @DisplayName("超過寬限期且查無訂單：判定為孤兒")
        void detectsAgedOrphan() {
            givenActivity();
            givenStock(650L, 300L);
            givenBinding(orphanBinding());
            when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
            when(orderRepository.findExistingOrderNos(any())).thenReturn(Set.of());

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.orphanBindings()).isEqualTo(1);
            assertThat(result.repairedBindings()).isZero();
            verify(metrics).recordOrphanBinding(ACTIVITY_ID, "detected");
        }

        @Test
        @DisplayName("仍在寬限期內：不算孤兒——訊息可能只是還在 MQ 佇列裡排隊")
        void ignoresRecentBinding() {
            givenActivity();
            givenStock(650L, 300L);
            givenBinding(orphanBinding());
            // 才產生 1 分鐘，遠短於 30 分鐘寬限期
            when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(1))));

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.orphanBindings()).isZero();
            // 這是最重要的斷言：貿然退掉還在處理中的請求，等訊息被消費就成了超賣
            verify(orderRepository, never()).findExistingOrderNos(any());
        }

        @Test
        @DisplayName("訂單確實存在：不是孤兒")
        void ignoresBindingWithExistingOrder() {
            givenActivity();
            givenStock(650L, 300L);
            givenBinding(orphanBinding());
            when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
            when(orderRepository.findExistingOrderNos(any())).thenReturn(Set.of("218896557439713280"));

            assertThat(service(defaultPolicy()).reconcile(ACTIVITY_ID).orphanBindings()).isZero();
        }

        @Test
        @DisplayName("訂單號無法解析產生時間：保守略過，不冒險退庫")
        void skipsBindingWithUnparseableOrderNo() {
            givenActivity();
            givenStock(650L, 300L);
            givenBinding(orphanBinding());
            when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.empty());

            assertThat(service(defaultPolicy()).reconcile(ACTIVITY_ID).orphanBindings()).isZero();
        }
    }

    @Nested
    @DisplayName("自動修復")
    class AutoRepair {

        @Test
        @DisplayName("預設不修復：只偵測並告警")
        void doesNotRepairByDefault() {
            givenAgedOrphan();

            ActivityReconciliation result = service(defaultPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.repairedBindings()).isZero();
            verify(stockRepository, never()).restore(anyLong(), anyLong(), anyInt(), anyString());
        }

        @Test
        @DisplayName("啟用後：以憑證自身攜帶的 userId 與數量退庫")
        void repairsUsingBindingOwnData() {
            givenAgedOrphan();
            when(stockRepository.restore(ACTIVITY_ID, 88L, 2, "req-orphan")).thenReturn(true);
            when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(650L, 652L);

            ActivityReconciliation result = service(repairEnabledPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.repairedBindings()).isEqualTo(1);
            verify(stockRepository).restore(ACTIVITY_ID, 88L, 2, "req-orphan");
            // 有庫存回補，售罄標記必須撤下
            verify(soldOutMarker).clear(ACTIVITY_ID);
        }

        @Test
        @DisplayName("舊格式憑證缺少數量：絕不硬退——退錯數字比不退更糟")
        void refusesToRepairIrreversibleBinding() {
            givenActivity();
            givenStock(650L, 300L);
            // quantity = 0 代表升級前的舊格式，只有訂單號
            givenBinding(new StockBinding("req-legacy", "218896557439713280", 0L, 0));
            when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
            when(orderRepository.findExistingOrderNos(any())).thenReturn(Set.of());

            ActivityReconciliation result = service(repairEnabledPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.orphanBindings()).isEqualTo(1);
            assertThat(result.repairedBindings()).isZero();
            verify(stockRepository, never()).restore(anyLong(), anyLong(), anyInt(), anyString());
            verify(metrics).recordOrphanBinding(ACTIVITY_ID, "not-reversible");
        }

        @Test
        @DisplayName("超賣風險方向：一律不自動處理，下修餘量會讓合法請求無故失敗")
        void neverAutoRepairsOversellRisk() {
            givenActivity();
            givenStock(750L, 300L);
            givenNoBindings();

            ActivityReconciliation result = service(repairEnabledPolicy()).reconcile(ACTIVITY_ID);

            assertThat(result.verdict()).isEqualTo(ReconciliationVerdict.OVERSELL_RISK);
            verify(stockRepository, never()).restore(anyLong(), anyLong(), anyInt(), anyString());
        }
    }

    @Test
    @DisplayName("單一活動對帳失敗不中斷整輪——其他活動的偏差同樣需要被發現")
    void continuesAfterSingleActivityFailure() {
        SeckillActivity failing = activityBuilder().id(1L).build();
        SeckillActivity healthy = activityBuilder().id(2L).build();
        when(activityRepository.findForReconciliation(any())).thenReturn(List.of(failing, healthy));
        when(stockRepository.availableStock(1L)).thenThrow(new IllegalStateException("Redis 掛了"));
        when(stockRepository.availableStock(2L)).thenReturn(700L);
        when(orderRepository.sumActiveQuantity(2L)).thenReturn(300L);
        givenNoBindings();

        List<ActivityReconciliation> results = service(defaultPolicy()).reconcileAll();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().activityId()).isEqualTo(2L);
    }

    // ---- fixtures ----

    private StockReconciliationService service(ReconciliationPolicy policy) {
        return new StockReconciliationService(
                activityRepository, orderRepository, stockRepository, orderNoGenerator,
                soldOutMarker, metrics, policy,
                new SeckillPolicy(Duration.ofMinutes(15), Duration.ofHours(2), 200),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReconciliationPolicy defaultPolicy() {
        return new ReconciliationPolicy(Duration.ofMinutes(30), 500, false);
    }

    private static ReconciliationPolicy repairEnabledPolicy() {
        return new ReconciliationPolicy(Duration.ofMinutes(30), 500, true);
    }

    private static StockBinding orphanBinding() {
        return new StockBinding("req-orphan", "218896557439713280", 88L, 2);
    }

    private void givenActivity() {
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activityBuilder().build()));
    }

    private void givenStock(long available, long orderedQuantity) {
        when(stockRepository.availableStock(ACTIVITY_ID)).thenReturn(available);
        when(orderRepository.sumActiveQuantity(ACTIVITY_ID)).thenReturn(orderedQuantity);
    }

    private void givenAgedOrphan() {
        givenActivity();
        givenStock(650L, 300L);
        givenBinding(orphanBinding());
        when(orderNoGenerator.issuedAt(any())).thenReturn(Optional.of(NOW.minus(Duration.ofHours(2))));
        when(orderRepository.findExistingOrderNos(any())).thenReturn(Set.of());
    }

    @SuppressWarnings("unchecked")
    private void givenBinding(StockBinding binding) {
        doAnswer(invocation -> {
            ((Consumer<List<StockBinding>>) invocation.getArgument(2)).accept(List.of(binding));
            return null;
        }).when(stockRepository).scanBindings(eq(ACTIVITY_ID), anyInt(), any());
    }

    @SuppressWarnings("unchecked")
    private void givenNoBindings() {
        doAnswer(invocation -> null).when(stockRepository).scanBindings(anyLong(), anyInt(), any());
    }

    private static SeckillActivity.Builder activityBuilder() {
        return SeckillActivity.builder()
                .id(ACTIVITY_ID)
                .productId(2001L)
                .productName("對帳測試商品")
                .seckillPrice(new BigDecimal("100.00"))
                .totalStock(TOTAL_STOCK)
                .perUserLimit(2)
                .period(NOW.minusSeconds(3600), NOW.plusSeconds(3600))
                .status(ActivityStatus.ONLINE);
    }
}
