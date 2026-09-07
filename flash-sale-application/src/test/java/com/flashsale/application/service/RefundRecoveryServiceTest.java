package com.flashsale.application.service;

import com.flashsale.application.port.in.RefundExecutionUseCase;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnLine;
import com.flashsale.domain.aftersales.ReturnNo;
import com.flashsale.domain.aftersales.ReturnReason;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.ReturnStatus;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 退款補送（ADR-0031）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("退款補送")
class RefundRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T10:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(2);

    @Mock private ReturnRequestRepository returnRepository;
    @Mock private RefundExecutionUseCase refundExecution;

    private RefundRecoveryService service;

    @BeforeEach
    void setUp() {
        service = new RefundRecoveryService(returnRepository, refundExecution,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ReturnRequest stuck(String returnNo) {
        return ReturnRequest.restore(1L, ReturnNo.of(returnNo),
                OrderNo.of("220956648921890304"), 88L, "req-" + returnNo,
                ReturnReason.CHANGED_MIND, null, false,
                List.of(ReturnLine.of(1L, "商品一", new BigDecimal("990"), 1)),
                ReturnStatus.REFUNDING, null, NOW, NOW, null, NOW.minusSeconds(600), null, 0L);
    }

    @Test
    @DisplayName("只撈超過寬限期的：還在重試中的不該被搶著推")
    void queriesOnlyBeyondTheGracePeriod() {
        when(returnRepository.findStuckRefunds(any(), anyInt())).thenReturn(List.of());

        service.recoverStuckRefunds(GRACE, 50);

        ArgumentCaptor<Instant> before = ArgumentCaptor.forClass(Instant.class);
        verify(returnRepository).findStuckRefunds(before.capture(), anyInt());
        // 消費端的重試預算耗盡之前，那筆退款還在正常流程上
        assertThat(before.getValue()).isEqualTo(NOW.minus(GRACE));
    }

    @Test
    @DisplayName("單筆失敗不中斷整批——一筆退不掉不該讓其他人的錢也卡著")
    void oneFailureDoesNotStopTheBatch() {
        when(returnRepository.findStuckRefunds(any(), anyInt()))
                .thenReturn(List.of(stuck("RMA-220956648921890111"),
                        stuck("RMA-220956648921890222"), stuck("RMA-220956648921890333")));
        doAnswer(invocation -> {
            RefundRequestedEvent event = invocation.getArgument(0);
            if ("RMA-220956648921890222".equals(event.returnNo())) {
                throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "閘道暫時無法連線");
            }
            return null;
        }).when(refundExecution).execute(any());

        int recovered = service.recoverStuckRefunds(GRACE, 50);

        assertThat(recovered).isEqualTo(2);
        verify(refundExecution, times(3)).execute(any());
    }
}
