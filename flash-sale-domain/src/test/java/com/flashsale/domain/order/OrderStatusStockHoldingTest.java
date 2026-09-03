package com.flashsale.domain.order;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 訂單狀態與「是否佔用庫存」的對應。
 *
 * <p><b>這支測試守的是一條寫在兩個地方的規則。</b>
 * {@code OrderStatus.holdsStock()} 是權威定義，
 * 但對帳的 JPQL 必須把同一份清單硬編碼一次（查詢要能下推到資料庫）。
 *
 * <p>兩邊一旦不同步，後果是<b>對帳把正常出貨誤判成庫存洩漏</b>——
 * 而那個告警會每十分鐘響一次，直到有人放棄看它為止。
 * 加入 {@code SHIPPED} 與 {@code COMPLETED} 時就差點漏掉這件事。
 */
@DisplayName("訂單狀態與庫存佔用")
class OrderStatusStockHoldingTest {

    /**
     * 對帳 JPQL 裡硬編碼的清單。
     *
     * <p>新增訂單狀態時，這裡與
     * {@code OrderJpaRepository.sumActiveQuantityByActivity} 必須一起改。
     */
    private static final Set<OrderStatus> RECONCILIATION_QUERY_STATUSES = EnumSet.of(
            OrderStatus.PENDING_PAYMENT,
            OrderStatus.PAID,
            OrderStatus.SHIPPED,
            OrderStatus.COMPLETED,
            OrderStatus.REFUNDED);

    @Test
    @DisplayName("holdsStock() 與對帳查詢的狀態清單必須完全一致")
    void queryMatchesDomainDefinition() {
        Set<OrderStatus> holdingStock = Arrays.stream(OrderStatus.values())
                .filter(OrderStatus::holdsStock)
                .collect(java.util.stream.Collectors.toCollection(
                        () -> EnumSet.noneOf(OrderStatus.class)));

        assertThat(holdingStock)
                .as("兩邊不同步會讓對帳把正常出貨誤判成庫存洩漏")
                .isEqualTo(RECONCILIATION_QUERY_STATUSES);
    }

    @Test
    @DisplayName("出貨與完成仍佔用庫存——貨已離開倉庫，那批貨確實不在了")
    void shippedAndCompletedStillHoldStock() {
        assertThat(OrderStatus.SHIPPED.holdsStock()).isTrue();
        assertThat(OrderStatus.COMPLETED.holdsStock()).isTrue();
    }

    @Test
    @DisplayName("已退款仍佔用秒殺庫存——退回的貨進的是一般庫存，不是活動的 Redis 餘量")
    void refundedStillHoldsSeckillStock() {
        // ADR-0011 決策 3。回 false 會讓對帳報 STOCK_LEAKED，
        // 但實際上什麼都沒洩漏——從活動的角度看，那一件確實賣掉了
        assertThat(OrderStatus.REFUNDED.holdsStock()).isTrue();
        // 而且它不該再觸發庫存補償：庫存已經由退貨流程回補過了，
        // 再補一次就是超賣
        assertThat(OrderStatus.REFUNDED.requiresStockCompensation()).isFalse();
    }

    @Test
    @DisplayName("取消與失敗不佔用庫存，且都要退庫")
    void closedStatusesReleaseStock() {
        assertThat(OrderStatus.CANCELLED.holdsStock()).isFalse();
        assertThat(OrderStatus.FAILED.holdsStock()).isFalse();
        assertThat(OrderStatus.CANCELLED.requiresStockCompensation()).isTrue();
        assertThat(OrderStatus.FAILED.requiresStockCompensation()).isTrue();
    }

    @Test
    @DisplayName("每個狀態都必須被明確歸類，不可有漏網的")
    void everyStatusIsClassified() {
        // 新增狀態卻忘了想「它算不算佔用庫存」時，這條會失敗
        for (OrderStatus status : OrderStatus.values()) {
            boolean holds = status.holdsStock();
            boolean compensates = status.requiresStockCompensation();
            assertThat(holds || compensates)
                    .as("狀態 %s 既不佔用庫存也不需要補償，那它的庫存去哪了？", status)
                    .isTrue();
        }
    }
}
