package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.in.RefundExecutionUseCase;
import com.flashsale.domain.aftersales.event.RefundRequestedEvent;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 執行已核可的退款——退款 Saga 的慢車道（ADR-0011）。
 *
 * <p><b>併發刻意設為 1。</b>其他消費組都開 2 以上，這裡不行：
 * 同一張訂單的多筆退款若併行處理，「累計退款 ≤ 已付金額」的檢查
 * 會同時讀到舊值，樂觀鎖雖然擋得住，但代價是整批重試。
 * 退款不是高流量路徑——一天幾百筆的東西不需要為了吞吐冒這個險。
 *
 * <p>事件的 partition key 是<b>訂單號</b>，因此同一張訂單的退款本來就落在同一個分區，
 * 單執行緒消費即可保證有序。
 */
@Component
public class RefundConsumer {

    private static final Logger log = LoggerFactory.getLogger(RefundConsumer.class);

    private final RefundExecutionUseCase refundExecutionUseCase;
    private final ObjectMapper objectMapper;

    public RefundConsumer(RefundExecutionUseCase refundExecutionUseCase,
                          ObjectMapper objectMapper) {
        this.refundExecutionUseCase = refundExecutionUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_EVENT,
            groupId = "${flash-sale.mq.refund-group:aftersales-refund-executor}",
            concurrency = "${flash-sale.mq.refund-concurrency:1}")
    public void onDomainEvent(@Payload String payload,
                              @Header(name = KafkaTopics.HEADER_EVENT_TYPE, required = false)
                              String eventType) throws Exception {
        if (!RefundRequestedEvent.TYPE.equals(eventType)) {
            // 同一個 topic 承載多種事件，非本消費組關心的直接 ack
            return;
        }
        RefundRequestedEvent event = objectMapper.readValue(payload, RefundRequestedEvent.class);
        refundExecutionUseCase.execute(event);
        log.debug("已處理退款事件 returnNo={}", event.returnNo());
    }
}
