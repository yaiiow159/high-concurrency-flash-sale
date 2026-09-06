package com.flashsale.application.service;

import com.flashsale.application.config.SeckillPolicy;
import com.flashsale.application.port.in.ExpiredOrderCloseUseCase;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import com.flashsale.domain.shared.BusinessException;
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
    private final InventoryService inventoryService;
    private final EventOutbox eventOutbox;
    private final SeckillPolicy policy;
    private final Clock clock;

    public ExpiredOrderCloseService(OrderRepository orderRepository,
                                    InventoryService inventoryService,
                                    EventOutbox eventOutbox,
                                    SeckillPolicy policy,
                                    Clock clock) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.eventOutbox = eventOutbox;
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

        int closed = 0;
        for (Order order : expired) {
            if (closeOne(order, now)) {
                closed++;
            }
        }
        log.info("逾期關單完成：撈出 {} 筆，成功關閉 {} 筆", expired.size(), closed);
        return closed;
    }

    /** 把一般庫存直接退回可售池。 */
    private void restoreStandardInventory(Order order) {
        for (OrderLine line : order.lines()) {
            if (line.sourceActivityId() != null) {
                continue;
            }
            inventoryService.restore(InventoryService.RestoreCommand.forNormal(
                    line.skuId(), order.userId(), line.quantity(),
                    order.requestId(), order.orderNo().value()));
        }
    }

    private boolean closeOne(Order order, Instant now) {
        try {
            order.cancel(CLOSE_REASON, now);
            orderRepository.update(order);
            restoreStandardInventory(order);
            // 秒殺庫存的退庫事件與關單狀態同交易寫入，避免「關了單卻沒退庫」的漏洞。
            eventOutbox.append(order.pullDomainEvents());
            return true;
        } catch (BusinessException e) {
            // 訂單在撈取後、關單前被付款了——這是正常的競態，不需告警。
            log.debug("訂單 {} 已非待付款狀態，略過：{}", order.orderNo(), e.getMessage());
            return false;
        }
    }
}
