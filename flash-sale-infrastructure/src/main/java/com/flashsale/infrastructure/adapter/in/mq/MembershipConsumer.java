package com.flashsale.infrastructure.adapter.in.mq;

import com.flashsale.application.port.in.MembershipUseCase;
import com.flashsale.application.port.in.OrderQueryUseCase;
import com.flashsale.application.port.in.dto.OrderView;
import com.flashsale.domain.order.event.OrderCompletedEvent;
import com.flashsale.domain.shared.BusinessException;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/** 訂單完成時發積分。 */
@Component
public class MembershipConsumer {

    private static final Logger log = LoggerFactory.getLogger(MembershipConsumer.class);

    private final MembershipUseCase membershipUseCase;
    private final OrderQueryUseCase orderQueryUseCase;
    private final DomainEventRouter router;

    public MembershipConsumer(MembershipUseCase membershipUseCase,
                              OrderQueryUseCase orderQueryUseCase,
                              DomainEventRouter router) {
        this.membershipUseCase = membershipUseCase;
        this.orderQueryUseCase = orderQueryUseCase;
        this.router = router;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.membership-group:membership-points}",
            concurrency = "${flash-sale.mq.membership-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        router.route(payload, eventType, OrderCompletedEvent.TYPE,
                OrderCompletedEvent.class, this::award);
    }

    private void award(OrderCompletedEvent event) {
        OrderView order;
        try {
            order = orderQueryUseCase.findByOrderNo(event.orderNo(), event.userId());
        } catch (BusinessException notFound) {
            // 訂單查不到只可能是資料被外力刪除。往外丟會讓這則事件一直重試，
            // 而重試永遠不會成功——記下來並 ack，讓對帳去發現它。
            // 這是 fail-open：漏發積分的代價遠低於卡住整個分區
            log.warn("訂單 {} 已完成但查不到，積分未入帳", event.orderNo(), notFound);
            return;
        }

        long points = membershipUseCase.awardForOrder(
                event.userId(), event.orderNo(), order.totalAmount());
        if (points > 0) {
            log.debug("訂單 {} 入帳 {} 點", event.orderNo(), points);
        }
    }
}
