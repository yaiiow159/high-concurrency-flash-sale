package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.DistributedLock;
import com.flashsale.application.port.out.InventoryRepository;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
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
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 庫存預熱 —— 劃撥與 Redis 初始化的先後順序。
 *
 * <p>這裡守的是雙模型最容易安靜出錯的兩個地方：
 * <ol>
 *   <li><b>順序</b>：先動 MySQL 再寫 Redis。反過來的失敗模式是超賣，不可逆</li>
 *   <li><b>已釋放的活動不可再預熱</b>：劃撥流水會擋下重複劃撥，
 *       但擋不住 Redis 初始化，結果就是 Redis 有一批沒人付過帳的貨</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("庫存預熱")
class StockWarmupServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long ACTIVITY = 1001L;
    private static final long SKU = 2001L;

    @Mock
    private ActivityRepository activityRepository;
    @Mock
    private StockRepository stockRepository;
    @Mock
    private InventoryRepository inventoryRepository;
    @Mock
    private InventoryMetrics metrics;
    @Mock
    private DistributedLock distributedLock;
    @Mock
    private SoldOutMarker soldOutMarker;

    @Nested
    @DisplayName("劃撥與初始化的順序")
    class Ordering {

        @Test
        @DisplayName("先扣 MySQL 的可售量，才寫 Redis——反過來就是超賣")
        void allocatesBeforeInitialisingRedis() {
            givenActivity();
            givenAllocationSucceeds();

            service().warmUp(ACTIVITY, false);

            // 順序若對調，失敗時 Redis 會有一批 MySQL 沒扣過的量，
            // 兩條通道就會把同一批貨各賣一次
            var ordered = inOrder(inventoryRepository, stockRepository);
            ordered.verify(inventoryRepository).recordMovement(any());
            ordered.verify(stockRepository).initialize(anyLong(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("劃撥失敗就不寫 Redis——少賣可以補救，超賣不能")
        void doesNotInitialiseRedisWhenAllocationFails() {
            givenActivity();
            when(inventoryRepository.isReleased(anyLong(), anyLong())).thenReturn(false);
            when(inventoryRepository.recordMovement(any())).thenReturn(true);
            when(inventoryRepository.findBySkuId(SKU)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service().warmUp(ACTIVITY, false))
                    .isInstanceOf(BusinessException.class);

            verify(stockRepository, never()).initialize(anyLong(), anyInt(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("已釋放的活動")
    class AlreadyReleased {

        @Test
        @DisplayName("拒絕重新預熱，且連 Redis 都不碰")
        void refusesToWarmUpReleasedActivity() {
            givenActivity();
            when(inventoryRepository.isReleased(ACTIVITY, SKU)).thenReturn(true);

            assertThatThrownBy(() -> service().warmUp(ACTIVITY, false))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).errorCode())
                    .isEqualTo(ErrorCode.ACTIVITY_STOCK_ALREADY_RELEASED);

            // 這是重點：劃撥流水的唯一索引會讓重新劃撥被「安靜略過」，
            // 若沒有這道檢查，initialize 仍會照樣把庫存寫回 Redis
            verify(stockRepository, never()).initialize(anyLong(), anyInt(), any(), anyBoolean());
        }

        @Test
        @DisplayName("force=true 也不能繞過——覆寫的是 Redis，繞不過帳的問題")
        void forceDoesNotBypassTheGuard() {
            givenActivity();
            when(inventoryRepository.isReleased(ACTIVITY, SKU)).thenReturn(true);

            assertThatThrownBy(() -> service().warmUp(ACTIVITY, true))
                    .isInstanceOf(BusinessException.class);

            verify(stockRepository, never()).initialize(anyLong(), anyInt(), any(), anyBoolean());
        }
    }

    // ---- fixtures ----

    private StockWarmupService service() {
        when(distributedLock.executeWithLock(anyString(), any(), any(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(3)).get());
        return new StockWarmupService(activityRepository, stockRepository, inventoryRepository,
                new InventoryAllocator(inventoryRepository, metrics),
                distributedLock, soldOutMarker, SeckillPolicy.defaults(), CLOCK);
    }

    private void givenActivity() {
        when(activityRepository.findById(ACTIVITY)).thenReturn(Optional.of(SeckillActivity.builder()
                .id(ACTIVITY)
                .skuId(SKU)
                .productName("iPhone 16 Pro 秒殺專場")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000)
                .perUserLimit(2)
                .period(NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(1)))
                .status(ActivityStatus.ONLINE)
                .build()));
    }

    private void givenAllocationSucceeds() {
        when(inventoryRepository.isReleased(anyLong(), anyLong())).thenReturn(false);
        when(inventoryRepository.recordMovement(any())).thenReturn(true);
        when(inventoryRepository.findBySkuId(SKU)).thenReturn(Optional.of(
                com.flashsale.domain.inventory.Inventory.restore(SKU, 5000, 0, 0L)));
        when(inventoryRepository.save(any())).thenReturn(true);
        when(stockRepository.availableStock(ACTIVITY)).thenReturn(1000L);
    }
}
