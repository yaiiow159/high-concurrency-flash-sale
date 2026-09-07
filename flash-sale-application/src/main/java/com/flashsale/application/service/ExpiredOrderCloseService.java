package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.ExpiredOrderCloseUseCase;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** 逾期訂單關單服務。 */
@Service
public class ExpiredOrderCloseService implements ExpiredOrderCloseUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpiredOrderCloseService.class);
    private static final String CLOSE_REASON = "逾時未付款，系統自動關閉";

    private final OrderRepository orderRepository;
    private final OrderCloser orderCloser;
    private final SeckillPolicy policy;
    private final Clock clock;

    public ExpiredOrderCloseService(OrderRepository orderRepository,
                                    OrderCloser orderCloser,
                                    SeckillPolicy policy,
                                    Clock clock) {
        this.orderRepository = orderRepository;
        this.orderCloser = orderCloser;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public int closeExpiredOrders() {
        Instant now = clock.instant();
        Instant deadline = now.minus(policy.paymentWindow());

        List<Order> expired =
                orderRepository.findExpiredPendingOrders(deadline, policy.compensationBatchSize());
        if (expired.isEmpty()) {
            return 0;
        }

        // 不逐筆 catch：撈出來的本來就是待付款，Order.cancel 在這裡不可能拒絕；
        // 真正的競態（付款回調搶先 commit）是樂觀鎖例外，讓它把整批回滾、30 秒後重跑，
        // 而不是被吞掉之後在 commit 時變成 UnexpectedRollbackException
        for (Order order : expired) {
            orderCloser.close(order, CLOSE_REASON, now);
        }
        log.info("逾期關單完成：關閉 {} 筆", expired.size());
        return expired.size();
    }
}
