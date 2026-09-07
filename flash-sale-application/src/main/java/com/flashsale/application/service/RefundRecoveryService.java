package com.flashsale.application.service;

import com.flashsale.application.port.in.RefundExecutionUseCase;
import com.flashsale.application.port.in.RefundRecoveryUseCase;
import com.flashsale.application.port.out.ReturnRequestRepository;
import com.flashsale.domain.aftersales.ReturnRequest;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 補送卡住的退款——退款的最終送達由這裡保證，不由佇列重試保證（ADR-0031）。 */
@Service
public class RefundRecoveryService implements RefundRecoveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(RefundRecoveryService.class);

    private final ReturnRequestRepository returnRepository;
    private final RefundExecutionUseCase refundExecution;
    private final Clock clock;

    public RefundRecoveryService(ReturnRequestRepository returnRepository,
                                 RefundExecutionUseCase refundExecution,
                                 Clock clock) {
        this.returnRepository = returnRepository;
        this.refundExecution = refundExecution;
        this.clock = clock;
    }

    /** {@inheritDoc} */
    @Override
    public int recoverStuckRefunds(Duration settlementGrace, int batchSize) {
        Instant now = clock.instant();
        List<ReturnRequest> stuck = returnRepository.findStuckRefunds(
                now.minus(settlementGrace), batchSize);

        int recovered = 0;
        for (ReturnRequest request : stuck) {
            // 單筆失敗不中斷整批：閘道對某一筆的拒絕不該讓其他人的錢也繼續卡著
            try {
                refundExecution.execute(RefundRequestedEvent.of(request, now));
                recovered++;
            } catch (RuntimeException e) {
                log.error("補送退款失敗，下一輪再試 returnNo={}", request.returnNo(), e);
            }
        }
        if (!stuck.isEmpty()) {
            log.warn("本輪補送退款 {} 筆，成功 {} 筆", stuck.size(), recovered);
        }
        return recovered;
    }
}
