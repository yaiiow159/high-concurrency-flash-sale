package com.flashsale.application.service;

import com.flashsale.application.port.in.FulfillmentUseCase;
import com.flashsale.application.port.in.dto.ShipmentView;
import com.flashsale.application.port.out.EventOutbox;
import com.flashsale.application.port.out.OrderRepository;
import com.flashsale.application.port.out.ShipmentNoGenerator;
import com.flashsale.application.port.out.ShipmentRepository;
import com.flashsale.domain.fulfillment.Carrier;
import com.flashsale.domain.fulfillment.Shipment;
import com.flashsale.domain.fulfillment.ShipmentStatus;
import com.flashsale.domain.order.Order;
import com.flashsale.domain.order.OrderNo;
import com.flashsale.domain.order.OrderStatus;
import com.flashsale.domain.order.event.OrderPaidEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.domain.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 履約服務。 */
@Service
public class FulfillmentService implements FulfillmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(FulfillmentService.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentNoGenerator shipmentNoGenerator;
    private final OrderRepository orderRepository;
    private final EventOutbox eventOutbox;
    private final Clock clock;

    public FulfillmentService(ShipmentRepository shipmentRepository,
                              ShipmentNoGenerator shipmentNoGenerator,
                              OrderRepository orderRepository,
                              EventOutbox eventOutbox,
                              Clock clock) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentNoGenerator = shipmentNoGenerator;
        this.orderRepository = orderRepository;
        this.eventOutbox = eventOutbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void prepareShipment(OrderPaidEvent event) {
        Shipment shipment = Shipment.prepare(shipmentNoGenerator.next(),
                event.orderNo(), event.userId(), clock.instant());

        // saveIfAbsent 回 empty 代表這張訂單已經有出貨單了。
        // 那是重複投遞，不是錯誤——直接返回，不要拋例外讓訊息進 DLQ。
        Optional<Shipment> created = shipmentRepository.saveIfAbsent(shipment);
        if (created.isEmpty()) {
            log.debug("訂單 {} 已有出貨單，略過", event.orderNo());
            return;
        }
        log.info("已建立出貨單 orderNo={}, shipmentNo={}",
                event.orderNo(), created.get().shipmentNo().value());
    }

    /** 交付承運商，並把訂單推進到「已出貨」。 */
    @Override
    @Transactional
    public ShipmentView dispatch(String orderNo, Carrier carrier, String trackingNumber) {
        Shipment shipment = requireShipment(orderNo);
        Order order = requireOrder(orderNo);

        // SHIPPED 也放行，因為重新派送會再次走到這裡（配送失敗後重送），
        // 此時訂單早已推進過。下面只在訂單還停在 PAID 時才轉狀態。
        if (order.status() != OrderStatus.PAID && order.status() != OrderStatus.SHIPPED) {
            throw new BusinessException(ErrorCode.ORDER_NOT_SHIPPABLE,
                    "訂單 %s 目前是 %s，無法出貨".formatted(orderNo, order.status()));
        }

        Instant now = clock.instant();
        shipment.dispatch(carrier, trackingNumber, now);
        shipmentRepository.update(shipment);

        if (order.status() == OrderStatus.PAID) {
            order.ship(now);
            orderRepository.update(order);
            eventOutbox.append(order.pullDomainEvents());
        }

        log.info("訂單 {} 已出貨 carrier={}, tracking={}, 第 {} 次派送",
                orderNo, carrier, trackingNumber, shipment.dispatchCount());
        return ShipmentView.from(shipment);
    }

    @Override
    @Transactional
    public ShipmentView markDelivered(String orderNo) {
        Shipment shipment = requireShipment(orderNo);
        Order order = requireOrder(orderNo);

        Instant now = clock.instant();
        shipment.deliver(now);
        shipmentRepository.update(shipment);

        // 訂單完成是退貨期限的起算點，因此必須與送達同一個交易——
        // 只更新出貨單的話，退貨期限就少了起算依據
        order.complete(now);
        orderRepository.update(order);
        eventOutbox.append(order.pullDomainEvents());

        log.info("訂單 {} 已送達", orderNo);
        return ShipmentView.from(shipment);
    }

    /** 配送失敗。 */
    @Override
    @Transactional
    public ShipmentView markFailed(String orderNo, String reason) {
        Shipment shipment = requireShipment(orderNo);
        shipment.markFailed(reason);
        shipmentRepository.update(shipment);

        log.warn("訂單 {} 配送失敗：{}（已派送 {} 次）", orderNo, reason, shipment.dispatchCount());
        return ShipmentView.from(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    public ShipmentView findForUser(String orderNo, Long userId) {
        Shipment shipment = requireShipment(orderNo);
        shipment.requireOwnedBy(userId);
        return ShipmentView.from(shipment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentView> listByStatus(ShipmentStatus status, int limit) {
        // 上限夾住：這是管理端點，但沒有上限的話一次查詢就能把整張表撈進記憶體
        int safeLimit = Math.clamp(limit, 1, 200);
        return shipmentRepository.findByStatus(status, safeLimit).stream()
                .map(ShipmentView::from)
                .toList();
    }

    private Shipment requireShipment(String orderNo) {
        return shipmentRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHIPMENT_NOT_FOUND));
    }

    private Order requireOrder(String orderNo) {
        return orderRepository.findByOrderNo(OrderNo.of(orderNo))
                .orElseThrow(() -> new BusinessException(ErrorCode.ORDER_NOT_FOUND));
    }
}
