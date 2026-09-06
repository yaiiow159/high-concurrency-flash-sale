package com.flashsale.infrastructure.adapter.in.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flashsale.application.port.in.OrderCreationUseCase;
import com.flashsale.application.port.out.message.SeckillOrderMessage;
import com.flashsale.infrastructure.adapter.out.mq.KafkaTopics;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 建單訊息消費端——削峰後的實際執行者。 */
@Component
public class SeckillOrderConsumer {

    private static final String MDC_ORDER_NO = "orderNo";

    private final OrderCreationUseCase orderCreationUseCase;
    private final ObjectMapper objectMapper;

    public SeckillOrderConsumer(OrderCreationUseCase orderCreationUseCase, ObjectMapper objectMapper) {
        this.orderCreationUseCase = orderCreationUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = KafkaTopics.ORDER_CREATE,
            groupId = "${flash-sale.mq.order-create-group:seckill-order-creator}",
            concurrency = "${flash-sale.mq.order-create-concurrency:6}")
    public void onMessage(String payload) throws Exception {
        SeckillOrderMessage message = objectMapper.readValue(payload, SeckillOrderMessage.class);
        MDC.put(MDC_ORDER_NO, message.orderNo());
        try {
            orderCreationUseCase.createFrom(message);
        } finally {
            // 執行緒會被重複使用，不清掉會讓下一則訊息的日誌掛著上一筆的訂單號。
            MDC.remove(MDC_ORDER_NO);
        }
    }
}
