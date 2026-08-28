package com.flashsale.infrastructure.scheduler;

import com.flashsale.application.port.in.ExpiredOrderCloseUseCase;
import com.flashsale.application.port.out.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 逾期訂單關單排程。
 *
 * <p>秒殺的預扣庫存若不回收，商品會陷入「賣不掉又下不了架」的死局——
 * 搶到卻不付款的使用者佔著庫存，真正想買的人卻只看得到售罄。
 *
 * <p>與 Outbox 中繼一樣採跨節點互斥：多個節點同時關單雖然有樂觀鎖兜底不會出錯，
 * 但會產生大量無謂的版本衝突與資料庫壓力。
 */
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

    /**
     * 排程方法<b>絕不可讓例外逸出</b>。
     *
     * <p>Spring 的 {@code ScheduledExecutorService} 在任務拋出未捕捉例外時，
     * 會直接取消該任務的後續排程——關單功能會從此靜默停擺，
     * 沒有錯誤日誌、沒有告警，直到有人發現庫存莫名其妙一直不回來。
     */
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
