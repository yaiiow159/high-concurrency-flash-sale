package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.inventory.Inventory;
import com.flashsale.domain.inventory.InventoryMovement;
import com.flashsale.domain.inventory.InventoryMovementType;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 庫存劃撥與釋放。
 *
 * <p>這裡守的是 ADR-0008 的核心主張：秒殺庫存是從一般庫存<b>切出來</b>的獨立額度，
 * 不是同一批貨的兩個視角。切出去、收回來這兩個動作若有任何一邊出錯，
 * 雙模型就退化成「兩個真實來源」，而那必然超賣。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("庫存劃撥與釋放")
class InventoryAllocationTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long ACTIVITY = 1001L;
    private static final long SKU = 2001L;

    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryMetrics metrics;
    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private DistributedLock distributedLock;

    @Nested
    @DisplayName("劃撥")
    class Allocate {

        @Test
        @DisplayName("流水與數字一起改：可售量減少、劃撥量增加")
        void movesQuantityIntoAllocation() {
            InventoryAllocator allocator = allocator();
            givenInventory(100, 0);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.save(any())).thenReturn(true);

            boolean applied = allocator.allocate(ACTIVITY, SKU, 30, NOW);

            assertThat(applied).isTrue();
            Inventory saved = capturedInventory();
            assertThat(saved.available()).isEqualTo(70);
            assertThat(saved.allocated()).isEqualTo(30);
        }

        @Test
        @DisplayName("重覆預熱不會重複劃撥——流水的唯一索引就是冪等閘門")
        void isIdempotent() {
            InventoryAllocator allocator = allocator();
            when(inventoryRepository.recordMovement(any())).thenReturn(false);

            boolean applied = allocator.allocate(ACTIVITY, SKU, 30, NOW);

            assertThat(applied).isFalse();
            // 關鍵：流水說「做過了」時，庫存數字一根寒毛都不能動
            verify(inventoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("可售量不足以劃撥時拋錯，不會硬切出一個負數")
        void refusesOverAllocation() {
            InventoryAllocator allocator = allocator();
            givenInventory(10, 0);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);

            assertThatThrownBy(() -> allocator.allocate(ACTIVITY, SKU, 30, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INSUFFICIENT_INVENTORY_TO_ALLOCATE);

            verify(inventoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("樂觀鎖衝突會重讀重試，用最新的數字重新檢查一次")
        void retriesOnVersionConflict() {
            InventoryAllocator allocator = allocator();
            givenInventory(100, 0);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.save(any())).thenReturn(false, true);

            boolean applied = allocator.allocate(ACTIVITY, SKU, 30, NOW);

            assertThat(applied).isTrue();
            verify(inventoryRepository, org.mockito.Mockito.times(2)).save(any());
        }

        @Test
        @DisplayName("持續衝突不靜默放過——低頻操作連撞三次代表有非預期的併發來源")
        void failsAfterRepeatedConflicts() {
            InventoryAllocator allocator = allocator();
            givenInventory(100, 0);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.save(any())).thenReturn(false);

            assertThatThrownBy(() -> allocator.allocate(ACTIVITY, SKU, 30, NOW))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("尚未建帳的 SKU 不可劃撥")
        void requiresExistingInventory() {
            InventoryAllocator allocator = allocator();
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.findBySkuId(SKU)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> allocator.allocate(ACTIVITY, SKU, 30, NOW))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.INVENTORY_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("釋放")
    class Release {

        @Test
        @DisplayName("未售出的量回到可售池，賣掉的量從總帳消失")
        void returnsUnsoldToAvailable() {
            InventoryAllocator allocator = allocator();
            givenInventory(70, 30);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.save(any())).thenReturn(true);

            allocator.release(ACTIVITY, SKU, 30, 12, NOW);

            Inventory saved = capturedInventory();
            assertThat(saved.available()).isEqualTo(82);
            assertThat(saved.allocated()).isZero();
            assertThat(saved.totalOnHand()).isEqualTo(100 - 18);
        }

        @Test
        @DisplayName("流水同時記下「回到可售的量」與「從劃撥扣掉的量」——兩者本來就不同")
        void ledgerRecordsBothSides() {
            InventoryAllocator allocator = allocator();
            givenInventory(70, 30);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.save(any())).thenReturn(true);

            allocator.release(ACTIVITY, SKU, 30, 12, NOW);

            ArgumentCaptor<InventoryMovement> captor =
                    ArgumentCaptor.forClass(InventoryMovement.class);
            verify(inventoryRepository).recordMovement(captor.capture());
            InventoryMovement movement = captor.getValue();

            assertThat(movement.type()).isEqualTo(InventoryMovementType.RELEASE);
            assertThat(movement.availableDelta()).isEqualTo(12);
            assertThat(movement.allocatedDelta()).isEqualTo(-30);
        }

        @Test
        @DisplayName("重覆釋放不會重複歸還")
        void isIdempotent() {
            InventoryAllocator allocator = allocator();
            when(inventoryRepository.recordMovement(any())).thenReturn(false);

            assertThat(allocator.release(ACTIVITY, SKU, 30, 12, NOW)).isFalse();
            verify(inventoryRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("釋放時機")
    class ReleaseTiming {

        @Test
        @DisplayName("以 Redis 當下的餘量作為未售量")
        void usesRedisRemainingAsUnsold() {
            StockReleaseService service = releaseService();
            givenActivity();
            when(inventoryRepository.findAllocatedQuantity(ACTIVITY, SKU))
                    .thenReturn(Optional.of(1000));
            when(stockRepository.availableStock(ACTIVITY)).thenReturn(994L);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.findBySkuId(SKU))
                    .thenReturn(Optional.of(Inventory.restore(SKU, 4000, 1000, 0L)));
            when(inventoryRepository.save(any())).thenReturn(true);

            assertThat(service.release(ACTIVITY)).isTrue();

            Inventory saved = capturedInventory();
            assertThat(saved.available()).isEqualTo(4994);
            assertThat(saved.allocated()).isZero();
        }

        @Test
        @DisplayName("庫存鍵已過期時當成全數售出——猜一個數字等於編造帳目")
        void treatsExpiredKeyAsFullySold() {
            StockReleaseService service = releaseService();
            givenActivity();
            when(inventoryRepository.findAllocatedQuantity(ACTIVITY, SKU))
                    .thenReturn(Optional.of(1000));
            // -1 代表鍵不存在，與「餘量為 0」是不同的意思
            when(stockRepository.availableStock(ACTIVITY)).thenReturn(-1L);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.findBySkuId(SKU))
                    .thenReturn(Optional.of(Inventory.restore(SKU, 4000, 1000, 0L)));
            when(inventoryRepository.save(any())).thenReturn(true);

            service.release(ACTIVITY);

            Inventory saved = capturedInventory();
            assertThat(saved.available()).as("一件都不退回").isEqualTo(4000);
            assertThat(saved.allocated()).isZero();
        }

        @Test
        @DisplayName("活動還沒過緩衝期就不可釋放——排程會過濾，手動觸發不會")
        void refusesToReleaseBeforeCooldown() {
            StockReleaseService service = releaseService();
            // 這場活動一小時前才結束，緩衝期是兩小時
            when(activityRepository.findById(ACTIVITY)).thenReturn(Optional.of(activityEndingAt(
                    NOW.minus(Duration.ofHours(1)))));

            assertThatThrownBy(() -> service.release(ACTIVITY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACTIVITY_NOT_COOLED_DOWN);

            // 關鍵：什麼都不能動。丟棄 Redis 鍵會讓進行中的補償無處可退
            verify(stockRepository, never()).discard(anyLong());
            verify(inventoryRepository, never()).recordMovement(any());
        }

        @Test
        @DisplayName("剛好過緩衝期的邊界可以釋放")
        void allowsReleaseExactlyAtCooldownBoundary() {
            StockReleaseService service = releaseService();
            when(activityRepository.findById(ACTIVITY)).thenReturn(Optional.of(activityEndingAt(
                    NOW.minus(Duration.ofHours(2)))));
            when(inventoryRepository.findAllocatedQuantity(ACTIVITY, SKU))
                    .thenReturn(Optional.of(1000));
            when(stockRepository.availableStock(ACTIVITY)).thenReturn(100L);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            givenInventory(4000, 1000);
            when(inventoryRepository.save(any())).thenReturn(true);

            assertThat(service.release(ACTIVITY)).isTrue();
        }

        @Test
        @DisplayName("沒有劃撥紀錄的舊活動不做任何事，也不碰 Redis")
        void skipsActivitiesWithoutAllocation() {
            StockReleaseService service = releaseService();
            givenActivity();
            when(inventoryRepository.findAllocatedQuantity(ACTIVITY, SKU))
                    .thenReturn(Optional.empty());

            assertThat(service.release(ACTIVITY)).isFalse();
            verify(stockRepository, never()).discard(anyLong());
        }

        @Test
        @DisplayName("先落 MySQL 才丟棄 Redis 鍵——順序反了會讓未售量兩邊都不存在")
        void discardsRedisKeyOnlyAfterDatabaseSucceeds() {
            StockReleaseService service = releaseService();
            givenActivity();
            when(inventoryRepository.findAllocatedQuantity(ACTIVITY, SKU))
                    .thenReturn(Optional.of(1000));
            when(stockRepository.availableStock(ACTIVITY)).thenReturn(100L);
            // 已釋放過：MySQL 沒有改動，因此 Redis 鍵也不該被丟棄
            when(inventoryRepository.recordMovement(any())).thenReturn(false);

            assertThat(service.release(ACTIVITY)).isFalse();
            verify(stockRepository, never()).discard(anyLong());
        }
    }

    // ---- fixtures ----

    private InventoryAllocator allocator() {
        return new InventoryAllocator(inventoryRepository, metrics);
    }

    private StockReleaseService releaseService() {
        when(distributedLock.executeWithLock(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        return new StockReleaseService(activityRepository, stockRepository, inventoryRepository,
                allocator(), distributedLock, policy(), CLOCK);
    }

    private void givenInventory(int available, int allocated) {
        when(inventoryRepository.findBySkuId(SKU))
                .thenReturn(Optional.of(Inventory.restore(SKU, available, allocated, 0L)));
    }

    private void givenActivity() {
        when(activityRepository.findById(ACTIVITY))
                .thenReturn(Optional.of(activityEndingAt(NOW.minus(Duration.ofDays(1)))));
    }

    private SeckillActivity activityEndingAt(Instant endAt) {
        return SeckillActivity.builder()
                .id(ACTIVITY)
                .skuId(SKU)
                .productName("iPhone 16 Pro 秒殺專場")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000)
                .perUserLimit(2)
                .period(endAt.minus(Duration.ofDays(1)), endAt)
                .status(ActivityStatus.ONLINE)
                .build();
    }

    private Inventory capturedInventory() {
        ArgumentCaptor<Inventory> captor = ArgumentCaptor.forClass(Inventory.class);
        verify(inventoryRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private SeckillPolicy policy() {
        return SeckillPolicy.defaults();
    }
}
