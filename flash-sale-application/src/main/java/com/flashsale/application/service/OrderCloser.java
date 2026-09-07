package com.flashsale.application.service;

import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.InventoryService;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderLine;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 關閉一張待付款訂單的完整動作：改狀態、退一般庫存、把秒殺退庫事件寫進 Outbox。
 * 逾時排程與後台手動關單共用同一份，兩邊各寫一次遲早會有一邊漏了退庫。
 * 刻意不加 {@code @Transactional}：呼叫端負責交易邊界，這裡的三步必須在同一個交易裡。
 */
@Service
public class OrderCloser {

    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final EventOutbox eventOutbox;

    public OrderCloser(OrderRepository orderRepository,
                       InventoryService inventoryService,
                       EventOutbox eventOutbox) {
        this.orderRepository = orderRepository;
        this.inventoryService = inventoryService;
        this.eventOutbox = eventOutbox;
    }

    /** 非待付款狀態由 {@link Order#cancel} 拋 {@code ILLEGAL_ORDER_STATE_TRANSITION}。 */
    public void close(Order order, String reason, Instant now) {
        order.cancel(reason, now);
        orderRepository.update(order);
        restoreStandardInventory(order);
        // 秒殺庫存的退庫事件與關單狀態同交易寫入，避免「關了單卻沒退庫」的漏洞
        eventOutbox.append(order.pullDomainEvents());
    }

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
}
