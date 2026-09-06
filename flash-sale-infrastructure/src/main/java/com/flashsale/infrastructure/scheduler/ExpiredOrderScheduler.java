package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.ExpiredOrderCloseUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 逾期訂單關單排程。 */
@Component
public class ExpiredOrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiredOrderScheduler.class);

    private static final String LOCK_KEY = "seckill:lock:expired-order";
    private static final Duration LOCK_LEASE = Duration.ofMinutes(5);

    private final ExpiredOrderCloseUseCase closeUseCase;
    private final DistributedLock distributedLock;

    public ExpiredOrderScheduler(ExpiredOrderCloseUseCase closeUseCase, DistributedLock distributedLock) {
        this.closeUseCase = closeUseCase;
        this.distributedLock = distributedLock;
    }

    @Scheduled(fixedDelayString = "${flash-sale.order.close-interval-ms:30000}")
    public void closeExpiredOrders() {
        distributedLock.tryExecuteWithLock(LOCK_KEY, LOCK_LEASE, this::runSafely);
    }

    /** 排程方法<b>絕不可讓例外逸出</b>。 */
    private void runSafely() {
        try {
            int closed = closeUseCase.closeExpiredOrders();
            if (closed > 0) {
                log.info("本輪關閉逾期訂單 {} 筆", closed);
            }
        } catch (RuntimeException e) {
            log.error("逾期關單執行失敗，下一輪將重試", e);
        }
    }
}
