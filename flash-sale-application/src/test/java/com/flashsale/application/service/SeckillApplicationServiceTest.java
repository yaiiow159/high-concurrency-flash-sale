package com.flashsale.application.service;

import com.flashsale.application.port.in.command.SeckillCommand;
import com.flashsale.application.port.in.dto.SeckillTicket;
import com.flashsale.application.port.out.ActivityRepository;
import com.flashsale.application.port.out.OrderNoGenerator;
import com.flashsale.application.port.out.OrderQueueDepth;
import com.flashsale.application.port.out.SeckillMessagePublisher;
import com.flashsale.application.port.out.SeckillRequestTracker;
import com.flashsale.application.port.out.SoldOutMarker;
import com.flashsale.application.port.out.StockRepository;
import com.flashsale.domain.activity.ActivityStatus;
import com.flashsale.domain.activity.SeckillActivity;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import com.flashsale.domain.stock.StockDeductionOutcome;
import com.flashsale.domain.stock.StockDeductionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 搶購主流程的單元測試。
 *
 * <p>整個 Use Case 的依賴都是介面，因此這裡不需要 Redis、Kafka、資料庫，
 * 也不需要啟動 Spring——這是六角架構最直接的回報。
 * 對應到現實：這些測試在 CI 上只要幾百毫秒，開發時可以每次存檔就跑。
 *
 * <p>時間以固定 {@link Clock} 注入，因此「活動已結束」這類案例可以被穩定重現，
 * 而不必依賴測試執行當下的系統時間。
 */
@ExtendWith(MockitoExtension.class)
class SeckillApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2025-06-01T10:30:00Z");
    private static final Long ACTIVITY_ID = 1001L;
    private static final Long USER_ID = 88L;
    private static final String REQUEST_ID = "req-001";
    private static final String ORDER_NO = "20250601000001";

    @Mock private ActivityRepository activityRepository;
    @Mock private StockRepository stockRepository;
    @Mock private SeckillMessagePublisher messagePublisher;
    @Mock private SeckillRequestTracker requestTracker;
    @Mock private SoldOutMarker soldOutMarker;
    @Mock private OrderQueueDepth queueDepth;
    @Mock private OrderNoGenerator orderNoGenerator;
    @Mock private SeckillMetrics metrics;

    private SeckillApplicationService service;

    @BeforeEach
    void setUp() {
        service = new SeckillApplicationService(
                activityRepository, stockRepository, messagePublisher, requestTracker,
                soldOutMarker, queueDepth, orderNoGenerator, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("正常流程：扣減成功後投遞訊息並回傳受理憑證")
    void acceptsRequestWhenStockDeducted() {
        givenPurchasableActivity();
        givenOrderNoGenerated();
        givenDeductionResult(StockDeductionResult.success(ORDER_NO));

        SeckillTicket ticket = service.attempt(command());

        assertThat(ticket.orderNo()).isEqualTo(ORDER_NO);
        verify(requestTracker).markAccepted(ORDER_NO, USER_ID);
        verify(messagePublisher).publish(any());
    }

    @Test
    @DisplayName("本機已標記售罄：連 Redis 都不碰就直接拒絕")
    void shortCircuitsWhenLocallyMarkedSoldOut() {
        when(soldOutMarker.isSoldOut(ACTIVITY_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.SOLD_OUT);

        // 這正是第一層漏斗存在的意義：售罄後的洪峰不該打到 Redis 與資料庫。
        verify(activityRepository, never()).findById(anyLong());
        verify(stockRepository, never()).deduct(anyLong(), anyLong(), anyInt(), anyInt(), anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 回報售罄：立刻豎起本機標記，保護後續請求")
    void raisesLocalMarkerWhenRedisReportsSoldOut() {
        givenPurchasableActivity();
        givenOrderNoGenerated();
        givenDeductionResult(StockDeductionResult.rejected(StockDeductionOutcome.SOLD_OUT));

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class);

        verify(soldOutMarker).markSoldOut(ACTIVITY_ID);
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    @DisplayName("重送相同 requestId：回放首次的訂單號，而不是回報重複請求錯誤")
    void replaysOriginalOrderNoOnDuplicateRequest() {
        givenPurchasableActivity();
        givenOrderNoGenerated();
        givenDeductionResult(StockDeductionResult.duplicate("ORIGINAL-ORDER-NO"));

        SeckillTicket ticket = service.attempt(command());

        // 使用者連點兩次不該被懲罰——他應該看到同一張訂單。
        assertThat(ticket.orderNo()).isEqualTo("ORIGINAL-ORDER-NO");
        // 首次請求已投遞過訊息，重送不可再投一次，否則消費端會收到重複建單訊息。
        verify(messagePublisher, never()).publish(any());
    }

    @Test
    @DisplayName("訊息投遞失敗：必須退回庫存，否則就是永久少賣")
    void compensatesStockWhenPublishFails() {
        givenPurchasableActivity();
        givenOrderNoGenerated();
        givenDeductionResult(StockDeductionResult.success(ORDER_NO));
        doThrow(new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED))
                .when(messagePublisher).publish(any());
        when(stockRepository.restore(ACTIVITY_ID, USER_ID, 1, REQUEST_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MESSAGE_PUBLISH_FAILED);

        verify(stockRepository).restore(ACTIVITY_ID, USER_ID, 1, REQUEST_ID);
        // 有庫存回補，售罄標記必須撤下，否則退回的量會搶不到。
        verify(soldOutMarker).clear(ACTIVITY_ID);
        verify(requestTracker).markFailed(eq(ORDER_NO), anyString());
    }

    @Test
    @DisplayName("補償本身失敗：不可掩蓋原始錯誤，否則排查時看不到真正的故障點")
    void originalErrorSurvivesCompensationFailure() {
        givenPurchasableActivity();
        givenOrderNoGenerated();
        givenDeductionResult(StockDeductionResult.success(ORDER_NO));
        doThrow(new BusinessException(ErrorCode.MESSAGE_PUBLISH_FAILED))
                .when(messagePublisher).publish(any());
        when(stockRepository.restore(anyLong(), anyLong(), anyInt(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.STOCK_SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.MESSAGE_PUBLISH_FAILED);

        verify(metrics).recordCompensation(eq(ACTIVITY_ID), anyString(), eq(false));
    }

    @Test
    @DisplayName("活動不存在：回 ACTIVITY_NOT_FOUND，不進入扣減流程")
    void rejectsUnknownActivity() {
        when(soldOutMarker.isSoldOut(ACTIVITY_ID)).thenReturn(false);
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACTIVITY_NOT_FOUND);
    }

    @Test
    @DisplayName("活動已結束：以注入的固定時鐘判定，不依賴系統時間")
    void rejectsEndedActivity() {
        when(soldOutMarker.isSoldOut(ACTIVITY_ID)).thenReturn(false);
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(
                activityBuilder()
                        .period(NOW.minusSeconds(7200), NOW.minusSeconds(3600))
                        .build()));

        assertThatThrownBy(() -> service.attempt(command()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.ACTIVITY_ENDED);
    }

    private void givenPurchasableActivity() {
        when(soldOutMarker.isSoldOut(ACTIVITY_ID)).thenReturn(false);
        when(activityRepository.findById(ACTIVITY_ID)).thenReturn(Optional.of(activityBuilder().build()));
    }

    private void givenOrderNoGenerated() {
        when(orderNoGenerator.next()).thenReturn(OrderNo.of(ORDER_NO));
    }

    private void givenDeductionResult(StockDeductionResult result) {
        when(stockRepository.deduct(eq(ACTIVITY_ID), eq(USER_ID), eq(1), anyInt(), eq(REQUEST_ID), anyString()))
                .thenReturn(result);
    }

    private static SeckillCommand command() {
        return new SeckillCommand(ACTIVITY_ID, USER_ID, 1, REQUEST_ID);
    }

    private static SeckillActivity.Builder activityBuilder() {
        return SeckillActivity.builder()
                .id(ACTIVITY_ID)
                .skuId(2001L)
                .productName("測試商品")
                .seckillPrice(new BigDecimal("29900.00"))
                .totalStock(1000)
                .perUserLimit(2)
                .period(NOW.minusSeconds(3600), NOW.plusSeconds(3600))
                .status(ActivityStatus.ONLINE);
    }
}
