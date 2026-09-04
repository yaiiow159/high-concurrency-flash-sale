package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 訂單完成時發積分。
 *
 * <p>與其他消費端共用同一個 topic 但各自的 group，因此都收到完整的事件流、
 * 互不影響。共用 group 的話一則事件只會被其中一個消費掉。
 *
 * <h2>事件只帶 ID，金額自己去查</h2>
 *
 * <p>{@link OrderCompletedEvent} 只有 {@code orderNo} 與 {@code userId}。
 * 積分要用實付金額算，而那個值從訂單讀當下的狀態取得——
 * 與 {@code ProductIndexConsumer} 同一個判斷（ADR-0012）：
 * 事件自帶內容會讓事件結構跟著消費端的需求演化，
 * 而佇列裡還躺著舊格式的事件。
 *
 * <h2>冪等在儲存庫，不在這裡</h2>
 *
 * <p>Outbox 是至少一次語意，重放是常態不是異常。這裡不做任何
 * 「處理過了嗎」的檢查——那種檢查在兩個並行的重放下會同時通過。
 * 真正的防線是 {@code point_transaction} 的
 * {@code (user_id, reason, ref_no)} 唯一索引。
 */
@Component
public class MembershipConsumer {

    private static final Logger log = LoggerFactory.getLogger(MembershipConsumer.class);

    private final MembershipUseCase membershipUseCase;
    private final OrderQueryUseCase orderQueryUseCase;
    private final ObjectMapper objectMapper;

    public MembershipConsumer(MembershipUseCase membershipUseCase,
                              OrderQueryUseCase orderQueryUseCase,
                              ObjectMapper objectMapper) {
        this.membershipUseCase = membershipUseCase;
        this.orderQueryUseCase = orderQueryUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.membership-group:membership-points}",
            concurrency = "${flash-sale.mq.membership-concurrency:2}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        if (!OrderCompletedEvent.TYPE.equals(eventType)) {
            // 其他事件直接 ack。order.created 在尖峰時每秒上萬筆，
            // 記一行就是一場日誌洪水
            return;
        }

        OrderCompletedEvent event = objectMapper.readValue(payload, OrderCompletedEvent.class);

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
